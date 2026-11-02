package de.privat.boom2local.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.privat.boom2local.app.bt.BluetoothLink
import de.privat.boom2local.app.bt.DeviceFinder
import de.privat.boom2local.app.bt.FoundDevice
import de.privat.boom2local.protocol.AuditEntry
import de.privat.boom2local.protocol.AuditKind
import de.privat.boom2local.protocol.AuditLog
import de.privat.boom2local.protocol.CaptureReport
import de.privat.boom2local.protocol.CaptureValidator
import de.privat.boom2local.protocol.Command
import de.privat.boom2local.protocol.CommandPolicy
import de.privat.boom2local.protocol.CommandSpec
import de.privat.boom2local.protocol.DecodedState
import de.privat.boom2local.protocol.DeviceProfile
import de.privat.boom2local.protocol.Frame
import de.privat.boom2local.protocol.Hex
import de.privat.boom2local.protocol.PolicyDecision
import de.privat.boom2local.protocol.RawMode
import de.privat.boom2local.protocol.StateDecoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Bluetooth link currently stands. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    class Connected(val uuid: String, val origin: String) : LinkState
    class Failed(val reason: String) : LinkState
}

/** Everything the screens render. */
class UiState(
    val loaded: LoadedProfile? = null,
    val hasOverride: Boolean = false,
    val bluetoothSupported: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val selected: FoundDevice? = null,
    val link: LinkState = LinkState.Idle,
    val deviceState: DecodedState? = null,
    val readOnly: Boolean = true,
    val rawMode: RawMode = RawMode.OFF,
    /** Pending argument values per command key, as the controls are moved. */
    val arguments: Map<String, Map<String, Int>> = emptyMap(),
    val audit: List<AuditEntry> = emptyList(),
    val capture: CaptureReport? = null,
    val message: String? = null,
) {
    val profile: DeviceProfile? get() = loaded?.profile
}

class Boom2ViewModel(application: Application) : AndroidViewModel(application) {

    private val audit = AuditLog(capacity = 800)
    private val store = ProfileStore(application)
    private val finder = DeviceFinder(application)

    private var link: BluetoothLink? = null
    private var frameJob: Job? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        reloadProfile()
    }

    // --- profile ----------------------------------------------------------------------

    fun reloadProfile() {
        val loaded = store.load()
        val defaults = loaded.profile?.let(::defaultArguments) ?: emptyMap()
        _ui.update {
            UiState(
                loaded = loaded,
                hasOverride = store.hasOverride(),
                bluetoothSupported = finder.bluetoothSupported,
                bluetoothEnabled = finder.bluetoothEnabled,
                permissionGranted = finder.hasConnectPermission(),
                readOnly = it.readOnly,
                rawMode = it.rawMode,
                arguments = defaults,
                audit = audit.snapshot(),
            )
        }
        loaded.error?.let { note("Profil: $it") }
        refreshDevices()
    }

    fun shippedProfileText(): String = try {
        store.shippedText()
    } catch (e: Exception) {
        "// Profil nicht lesbar: ${e.message}"
    }

    fun importProfile(text: String) {
        val result = store.saveOverride(text)
        if (!result.isUsable) {
            val detail = result.error ?: result.problems.joinToString("; ")
            note("Profil nicht uebernommen: $detail")
            return
        }
        audit.add(AuditKind.INFO, "Profil ersetzt", timestampMs = now())
        reloadProfile()
        note("Profil uebernommen.")
    }

    fun resetProfile() {
        store.clearOverride()
        audit.add(AuditKind.INFO, "Profil auf Lieferzustand zurueckgesetzt", timestampMs = now())
        reloadProfile()
        note("Mitgeliefertes Profil wird verwendet.")
    }

    private fun defaultArguments(profile: DeviceProfile): Map<String, Map<String, Int>> =
        profile.commands.associate { spec ->
            spec.key to spec.arguments.associate { arg ->
                arg.key to (arg.default ?: arg.choices.firstOrNull()?.value ?: arg.min)
            }
        }

    // --- devices ----------------------------------------------------------------------

    fun refreshDevices() {
        val profile = _ui.value.profile
        val devices = if (profile == null) emptyList() else finder.pairedDevices(profile)
        _ui.update {
            it.copyWith(
                bluetoothSupported = finder.bluetoothSupported,
                bluetoothEnabled = finder.bluetoothEnabled,
                permissionGranted = finder.hasConnectPermission(),
                devices = devices,
                selected = it.selected?.let { chosen ->
                    devices.firstOrNull { d -> d.address == chosen.address }
                } ?: devices.firstOrNull { d -> d.matchesProfile },
            )
        }
    }

    fun select(device: FoundDevice) {
        _ui.update { it.copyWith(selected = device) }
    }

    // --- connection -------------------------------------------------------------------

    fun connect() {
        val state = _ui.value
        val profile = state.profile ?: run { note("Kein brauchbares Profil geladen."); return }
        val device = state.selected ?: run { note("Kein Geraet ausgewaehlt."); return }
        if (!device.matchesProfile) {
            note("${device.name} passt nicht zum Profil ${profile.displayName}.")
            return
        }
        val candidates = finder.resolveServiceUuids(device, profile)
        if (candidates.isEmpty()) {
            note(
                "Die Box bietet keinen Dienst mit dem Praefix " +
                    "${profile.transport.uuidPrefix} an, und das Profil nennt keinen " +
                    "Rueckfall-Kandidaten.",
            )
            return
        }
        disconnect()
        val created = BluetoothLink(profile, audit, ::now)
        link = created
        _ui.update { it.copyWith(link = LinkState.Connecting) }
        frameJob = viewModelScope.launch {
            created.frames.collect { frame -> onFrame(frame) }
        }
        viewModelScope.launch {
            try {
                val uuid = created.connect(device.device, candidates.map { it.uuid })
                val origin = candidates.firstOrNull { it.uuid == uuid }?.origin ?: ""
                _ui.update {
                    it.copyWith(
                        link = LinkState.Connected(uuid.toString(), origin),
                        audit = audit.snapshot(),
                    )
                }
                runHandshake(profile)
                readState()
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                // Tear the failed link down completely rather than leaving a collector
                // and a coroutine scope behind for every attempt that did not work out.
                frameJob?.cancel()
                frameJob = null
                created.shutdown()
                if (link === created) link = null
                _ui.update {
                    it.copyWith(link = LinkState.Failed(reason), audit = audit.snapshot())
                }
            }
        }
    }

    fun disconnect() {
        frameJob?.cancel()
        frameJob = null
        // shutdown(), not close(): connect() always builds a fresh BluetoothLink, so this
        // instance is finished with and its coroutine scope has to go with it.
        link?.shutdown()
        link = null
        _ui.update { it.copyWith(link = LinkState.Idle, audit = audit.snapshot()) }
    }

    private suspend fun runHandshake(profile: DeviceProfile) {
        val active = link ?: return
        val policy = policy(profile)
        for (step in profile.framing.handshake) {
            when (val decision = policy.evaluateHandshake(step)) {
                is PolicyDecision.Denied -> {
                    audit.add(
                        AuditKind.BLOCKED,
                        "Handshake-Schritt ${step.command} abgelehnt: ${decision.message}",
                        rule = decision.rule,
                        timestampMs = now(),
                    )
                    return
                }

                PolicyDecision.Allowed -> {
                    active.send(Command.parse(step.command), Hex.decode(step.payload))
                    kotlinx.coroutines.delay(step.delayMsAfter.coerceIn(0L, 5_000L))
                }
            }
        }
        _ui.update { it.copyWith(audit = audit.snapshot()) }
    }

    private fun onFrame(frame: Frame) {
        val profile = _ui.value.profile ?: return
        if (frame.command == profile.stateResponseCommand) {
            val decoded = StateDecoder.decode(profile, frame)
            _ui.update { it.copyWith(deviceState = decoded, audit = audit.snapshot()) }
        } else {
            audit.add(
                AuditKind.INFO,
                "Antwort ${frame.command}, ${frame.body.size} Byte (im Profil nicht beschrieben)",
                hex = Hex.encode(frame.body),
                timestampMs = now(),
            )
            _ui.update { it.copyWith(audit = audit.snapshot()) }
        }
    }

    // --- commands ---------------------------------------------------------------------

    fun setReadOnly(value: Boolean) {
        audit.add(
            AuditKind.INFO,
            if (value) "Nur-Lesen-Modus eingeschaltet" else "Nur-Lesen-Modus ausgeschaltet",
            timestampMs = now(),
        )
        _ui.update { it.copyWith(readOnly = value, audit = audit.snapshot()) }
    }

    fun setRawMode(mode: RawMode) {
        audit.add(AuditKind.INFO, "Experten-Modus: $mode", timestampMs = now())
        _ui.update { it.copyWith(rawMode = mode, audit = audit.snapshot()) }
    }

    fun setArgument(commandKey: String, argumentKey: String, value: Int) {
        _ui.update { state ->
            val forCommand = (state.arguments[commandKey] ?: emptyMap()) + (argumentKey to value)
            state.copyWith(arguments = state.arguments + (commandKey to forCommand))
        }
    }

    fun readState() {
        val profile = _ui.value.profile ?: return
        val spec = profile.commands.firstOrNull { it.commandAddress == profile.stateRequestCommand }
        if (spec == null) {
            note("Das Profil erlaubt keine Status-Abfrage.")
            return
        }
        send(spec)
    }

    fun send(spec: CommandSpec) {
        val state = _ui.value
        val profile = state.profile ?: return
        val active = link
        val arguments = state.arguments[spec.key] ?: emptyMap()
        val policy = policy(profile)
        when (val decision = policy.evaluateSpec(spec, arguments)) {
            is PolicyDecision.Denied -> {
                audit.add(
                    AuditKind.BLOCKED,
                    "${spec.label} (${spec.command}) abgelehnt: ${decision.message}",
                    rule = decision.rule,
                    timestampMs = now(),
                )
                _ui.update { it.copyWith(audit = audit.snapshot()) }
                note("Abgelehnt (${decision.rule}): ${decision.message}")
                return
            }

            PolicyDecision.Allowed -> Unit
        }
        if (active == null || !active.isConnected) {
            note("Nicht verbunden.")
            return
        }
        val payload = try {
            policy.buildPayload(spec, arguments)
        } catch (e: IllegalArgumentException) {
            note("Payload konnte nicht gebaut werden: ${e.message}")
            return
        }
        viewModelScope.launch {
            try {
                active.send(spec.commandAddress, payload)
                _ui.update { it.copyWith(audit = audit.snapshot()) }
            } catch (e: Exception) {
                note("Senden fehlgeschlagen: ${e.message}")
                _ui.update { it.copyWith(audit = audit.snapshot()) }
            }
        }
    }

    /** The diagnostics screen's hand-entered command. Gated by the expert mode. */
    fun sendRaw(commandText: String, payloadText: String) {
        val state = _ui.value
        val profile = state.profile ?: return
        val command = try {
            Command.parse(commandText)
        } catch (e: IllegalArgumentException) {
            note("Befehl unlesbar: ${e.message}")
            return
        }
        val payload = try {
            Hex.decode(payloadText)
        } catch (e: IllegalArgumentException) {
            note("Payload unlesbar: ${e.message}")
            return
        }
        val policy = policy(profile)
        when (val decision = policy.evaluateRaw(command, payload)) {
            is PolicyDecision.Denied -> {
                audit.add(
                    AuditKind.BLOCKED,
                    "Roh-Befehl $command abgelehnt: ${decision.message}",
                    rule = decision.rule,
                    hex = Hex.encode(payload),
                    timestampMs = now(),
                )
                _ui.update { it.copyWith(audit = audit.snapshot()) }
                note("Abgelehnt (${decision.rule}): ${decision.message}")
            }

            PolicyDecision.Allowed -> {
                val active = link
                if (active == null || !active.isConnected) {
                    note("Nicht verbunden.")
                    return
                }
                viewModelScope.launch {
                    try {
                        active.send(command, payload)
                        _ui.update { it.copyWith(audit = audit.snapshot()) }
                    } catch (e: Exception) {
                        note("Senden fehlgeschlagen: ${e.message}")
                    }
                }
            }
        }
    }

    fun policyDescription(): List<String> {
        val profile = _ui.value.profile ?: return listOf("Kein Profil geladen.")
        return policy(profile).describe()
    }

    private fun policy(profile: DeviceProfile) = CommandPolicy(
        profile = profile,
        readOnly = _ui.value.readOnly,
        rawMode = _ui.value.rawMode,
    )

    // --- diagnostics ------------------------------------------------------------------

    fun analyzeCapture(hexText: String) {
        val profile = _ui.value.profile
        val framing = profile?.framing
        try {
            val report = if (framing == null) {
                CaptureValidator.analyzeHex(hexText)
            } else {
                CaptureValidator.analyzeHex(hexText, framing)
            }
            _ui.update { it.copyWith(capture = report) }
        } catch (e: IllegalArgumentException) {
            note("Mitschnitt unlesbar: ${e.message}")
        }
    }

    fun analyzeAuditLog() {
        val hex = audit.snapshot()
            .filter { it.kind == AuditKind.SENT || it.kind == AuditKind.RECEIVED }
            .mapNotNull { it.hex }
            .joinToString(" ")
        if (hex.isBlank()) {
            note("Noch keine Bytes im Protokoll.")
            return
        }
        analyzeCapture(hex)
    }

    fun auditExport(): String = audit.export()

    fun clearAudit() {
        audit.clear()
        _ui.update { it.copyWith(audit = audit.snapshot(), capture = null) }
    }

    fun onPermissionResult(granted: Boolean) {
        _ui.update { it.copyWith(permissionGranted = granted) }
        if (granted) refreshDevices()
    }

    fun note(message: String?) {
        _ui.update { it.copyWith(message = message) }
    }

    override fun onCleared() {
        frameJob?.cancel()
        link?.shutdown()
        link = null
        super.onCleared()
    }

    private fun now(): Long = System.currentTimeMillis()
}

/**
 * [UiState] is a plain class rather than a data class so that the Bluetooth device it
 * holds is never compared or hashed by Compose; this is the explicit copy it needs.
 */
private fun UiState.copyWith(
    loaded: LoadedProfile? = this.loaded,
    hasOverride: Boolean = this.hasOverride,
    bluetoothSupported: Boolean = this.bluetoothSupported,
    bluetoothEnabled: Boolean = this.bluetoothEnabled,
    permissionGranted: Boolean = this.permissionGranted,
    devices: List<FoundDevice> = this.devices,
    selected: FoundDevice? = this.selected,
    link: LinkState = this.link,
    deviceState: DecodedState? = this.deviceState,
    readOnly: Boolean = this.readOnly,
    rawMode: RawMode = this.rawMode,
    arguments: Map<String, Map<String, Int>> = this.arguments,
    audit: List<AuditEntry> = this.audit,
    capture: CaptureReport? = this.capture,
    message: String? = this.message,
): UiState = UiState(
    loaded = loaded,
    hasOverride = hasOverride,
    bluetoothSupported = bluetoothSupported,
    bluetoothEnabled = bluetoothEnabled,
    permissionGranted = permissionGranted,
    devices = devices,
    selected = selected,
    link = link,
    deviceState = deviceState,
    readOnly = readOnly,
    rawMode = rawMode,
    arguments = arguments,
    audit = audit,
    capture = capture,
    message = message,
)
