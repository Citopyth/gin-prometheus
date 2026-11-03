package de.privat.boom2local.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.privat.boom2local.app.bt.ControlTransport
import de.privat.boom2local.app.bt.DeviceFinder
import de.privat.boom2local.app.bt.GattTransport
import de.privat.boom2local.app.bt.RfcommTransport
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
import de.privat.boom2local.protocol.GattService
import de.privat.boom2local.protocol.Hex
import de.privat.boom2local.protocol.LengthSemantics
import de.privat.boom2local.protocol.PacketCodec
import de.privat.boom2local.protocol.PolicyDecision
import de.privat.boom2local.protocol.RawMode
import de.privat.boom2local.protocol.StateDecoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which pipe to carry the frames over. */
enum class TransportChoice { AUTO, RFCOMM, BLE }

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
    /** A fresh SDP query is running. */
    val sdpBusy: Boolean = false,
    /** What the last SDP query had to say, if anything went sideways. */
    val sdpNote: String? = null,
    /** Also try the standard Bluetooth profiles as connection targets. */
    val tryStandardProfiles: Boolean = false,
    val transport: TransportChoice = TransportChoice.AUTO,
    /** Send the profile's handshake after connecting. Off by default: see the UI note. */
    val useHandshake: Boolean = false,
    /** Use an authenticated RFCOMM socket instead of the profile's insecure one. */
    val secureSocket: Boolean = false,
    val probeBusy: Boolean = false,
    val probeResult: List<String> = emptyList(),
    /** GATT services seen on the last BLE attempt, successful or not. */
    val gattServices: List<GattService> = emptyList(),
    val message: String? = null,
) {
    val profile: DeviceProfile? get() = loaded?.profile
}

class Boom2ViewModel(application: Application) : AndroidViewModel(application) {

    private val audit = AuditLog(capacity = 800)
    private val store = ProfileStore(application)
    private val finder = DeviceFinder(application)

    private val appContext: Context = application.applicationContext
    private var link: ControlTransport? = null
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
        _ui.update { it.copyWith(selected = device, sdpNote = null) }
    }

    /**
     * Asks the speaker which services it really offers.
     *
     * The paired-device list only carries what Android cached at pairing time, and for a
     * speaker paired for audio that is often just the audio profiles. Without this, a
     * control service the cache never recorded is invisible — which is exactly the state
     * that makes every connection attempt fail against guessed UUIDs.
     */
    fun refreshSdp() {
        val state = _ui.value
        val profile = state.profile ?: return
        val selected = state.selected ?: run { note("Kein Geraet ausgewaehlt."); return }
        _ui.update { it.copyWith(sdpBusy = true, sdpNote = null) }
        viewModelScope.launch {
            val result = finder.refreshSdp(selected.device)
            val updated = finder.describe(selected.device, profile, result.uuids)
            audit.add(
                if (result.fresh) AuditKind.INFO else AuditKind.ERROR,
                "SDP-Abfrage ${selected.name}: ${result.uuids.size} Dienst(e), " +
                    "${updated.customUuids.size} herstellerspezifisch" +
                    (result.note?.let { " - $it" } ?: ""),
                hex = result.uuids.joinToString(" "),
                timestampMs = now(),
            )
            _ui.update { current ->
                current.copyWith(
                    devices = current.devices.map {
                        if (it.address == updated.address) updated else it
                    },
                    selected = updated,
                    sdpBusy = false,
                    sdpNote = result.note,
                    audit = audit.snapshot(),
                )
            }
        }
    }

    fun setTryStandardProfiles(value: Boolean) {
        _ui.update { it.copyWith(tryStandardProfiles = value) }
    }

    fun setUseHandshake(value: Boolean) {
        _ui.update { it.copyWith(useHandshake = value) }
    }

    fun setSecureSocket(value: Boolean) {
        _ui.update { it.copyWith(secureSocket = value) }
    }

    fun setTransport(choice: TransportChoice) {
        audit.add(AuditKind.INFO, "Transportweg: $choice", timestampMs = now())
        _ui.update { it.copyWith(transport = choice, audit = audit.snapshot()) }
    }

    /** The whole SDP picture of the selected device, for pasting into a bug report. */
    fun sdpExport(): String {
        val selected = _ui.value.selected ?: return "Kein Geraet ausgewaehlt."
        return buildString {
            appendLine("Geraet: ${selected.name}")
            appendLine("Adresse: ${selected.address}")
            appendLine("Passt zum Profil: ${selected.matchesProfile}")
            appendLine("Dienste laut SDP: ${selected.allUuids.size}")
            for (uuid in selected.allUuids) {
                val kind = if (uuid in selected.customUuids) "herstellerspezifisch" else "Standardprofil"
                appendLine("  $uuid  ($kind)")
            }
            val gatt = _ui.value.gattServices
            if (gatt.isEmpty()) {
                appendLine()
                appendLine("GATT: noch nicht abgefragt (einmal mit BLE verbinden).")
            } else {
                appendLine()
                appendLine("GATT-Dienste: ${gatt.size}")
                for (service in gatt) {
                    appendLine("  ${service.uuid}")
                    for (characteristic in service.characteristics) {
                        val flags = buildList {
                            if (characteristic.write) add("write")
                            if (characteristic.writeNoResponse) add("write-no-response")
                            if (characteristic.notify) add("notify")
                            if (characteristic.indicate) add("indicate")
                        }
                        appendLine(
                            "    ${characteristic.uuid}  " +
                                if (flags.isEmpty()) "(nur lesbar)" else flags.joinToString(","),
                        )
                    }
                }
            }
        }
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

        val rfcommCandidates = finder.resolveServiceUuids(
            found = device,
            profile = profile,
            includeStandardProfiles = state.tryStandardProfiles,
        ).map { RfcommTransport.UuidCandidate(it.uuid, it.origin) }

        // Classic first when the speaker advertises anything usable, because every
        // published description of this protocol is RFCOMM. BLE is the fallback, and the
        // only way to find out whether this model went the other way.
        val plan = mutableListOf<ControlTransport>()
        fun rfcomm() = RfcommTransport(
            profile = profile,
            audit = audit,
            candidates = rfcommCandidates,
            insecureOverride = if (state.secureSocket) false else null,
            clock = ::now,
        )
        fun gatt() = GattTransport(appContext, profile, audit, ::now)
        when (state.transport) {
            TransportChoice.RFCOMM -> plan += rfcomm()
            TransportChoice.BLE -> plan += gatt()
            TransportChoice.AUTO -> {
                if (rfcommCandidates.isNotEmpty()) plan += rfcomm()
                if (profile.ble.enabled) plan += gatt()
            }
        }
        if (plan.isEmpty()) {
            note(
                "Kein Transportweg uebrig: die Box bietet keinen RFCOMM-Dienst an und BLE " +
                    "ist im Profil abgeschaltet.",
            )
            return
        }

        disconnect()
        _ui.update { it.copyWith(link = LinkState.Connecting) }
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            for (transport in plan) {
                link = transport
                val collector = viewModelScope.launch {
                    launch { transport.frames.collect { frame -> onFrame(frame) } }
                    launch {
                        transport.disconnects.collect { reason ->
                            _ui.update {
                                if (it.link is LinkState.Connected) {
                                    it.copyWith(
                                        link = LinkState.Failed(
                                            "Verbindung verloren: $reason\n\nDie Box hat die " +
                                                "Verbindung angenommen und dann beendet, ohne zu " +
                                                "antworten. Das passiert, wenn sie mit den " +
                                                "gesendeten Bytes nichts anfangen kann.",
                                        ),
                                        audit = audit.snapshot(),
                                    )
                                } else {
                                    it.copyWith(audit = audit.snapshot())
                                }
                            }
                        }
                    }
                }
                frameJob = collector
                try {
                    val target = transport.connect(device.device)
                    _ui.update {
                        it.copyWith(
                            link = LinkState.Connected(
                                target.label,
                                "${transport.name} - ${target.detail}",
                            ),
                            gattServices = gattServicesOf(transport) ?: it.gattServices,
                            audit = audit.snapshot(),
                        )
                    }
                    runHandshake(profile)
                    readState()
                    return@launch
                } catch (e: Exception) {
                    failures += "${transport.name}: ${e.message ?: e.javaClass.simpleName}"
                    collector.cancel()
                    if (frameJob === collector) frameJob = null
                    val seen = gattServicesOf(transport)
                    transport.shutdown()
                    if (link === transport) link = null
                    _ui.update {
                        it.copyWith(
                            gattServices = seen ?: it.gattServices,
                            audit = audit.snapshot(),
                        )
                    }
                }
            }
            val reason = buildString {
                append(failures.joinToString("\n\n"))
                if (device.customUuids.isEmpty()) {
                    append(
                        "\n\nDie Box bietet laut SDP keinen herstellerspezifischen Dienst " +
                            "an. Drueck zuerst \"SDP neu abfragen\": die gespeicherte Liste " +
                            "stammt vom Koppeln und ist oft unvollstaendig.",
                    )
                }
            }
            _ui.update { it.copyWith(link = LinkState.Failed(reason), audit = audit.snapshot()) }
        }
    }

    private fun gattServicesOf(transport: ControlTransport): List<GattService>? =
        (transport as? GattTransport)?.discoveredServices?.takeIf { it.isNotEmpty() }

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
        if (!_ui.value.useHandshake) {
            if (profile.framing.handshake.isNotEmpty()) {
                audit.add(
                    AuditKind.INFO,
                    "Handshake uebersprungen (${profile.framing.handshake.size} Schritte " +
                        "im Profil, Schalter steht auf aus)",
                    timestampMs = now(),
                )
            }
            return
        }
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

    /**
     * Sends the state request under each length interpretation and reports which one the
     * speaker answers.
     *
     * The length field is the one part of the framing that no source pins down, and a
     * speaker that accepts the connection and then goes quiet is exactly what a frame it
     * cannot parse looks like. Trying both costs two packets and settles it.
     */
    fun probeFraming() {
        val state = _ui.value
        val profile = state.profile ?: return
        val transport = link
        if (transport == null || !transport.isConnected) {
            note("Nicht verbunden.")
            return
        }
        _ui.update { it.copyWith(probeBusy = true, probeResult = emptyList()) }
        viewModelScope.launch {
            val results = mutableListOf<String>()
            for (semantics in LengthSemantics.entries) {
                val codec = PacketCodec(
                    outboundHeader = Hex.decode(profile.framing.outboundHeader),
                    inboundHeader = Hex.decode(profile.framing.inboundHeader),
                    lengthSemantics = semantics,
                    maxFrameSize = profile.framing.maxFrameSize,
                )
                val frame = codec.encode(profile.stateRequestCommand)
                val before = audit.count(AuditKind.RECEIVED)
                audit.add(
                    AuditKind.INFO,
                    "Sondierung: Laengenfeld als $semantics gedeutet",
                    timestampMs = now(),
                )
                try {
                    transport.sendFrame(frame)
                } catch (e: Exception) {
                    results += "$semantics: Senden fehlgeschlagen (${e.message})"
                    break
                }
                delay(PROBE_WAIT_MS)
                val answered = audit.count(AuditKind.RECEIVED) - before
                results += if (answered > 0) {
                    "$semantics: ANTWORT, $answered Paket(e) - das ist die richtige Deutung."
                } else {
                    "$semantics: keine Antwort."
                }
                _ui.update { it.copyWith(audit = audit.snapshot(), probeResult = results.toList()) }
            }
            if (results.none { it.contains("ANTWORT") }) {
                results += "Keine Deutung brachte eine Antwort. Dann stimmt etwas anderes " +
                    "nicht: Header-Bytes, Pruefsumme, oder die Box erwartet zuerst einen " +
                    "Handshake."
            }
            _ui.update {
                it.copyWith(
                    probeBusy = false,
                    probeResult = results.toList(),
                    audit = audit.snapshot(),
                )
            }
        }
    }

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

    private companion object {
        /** How long to wait for an answer before calling a framing variant dead. */
        const val PROBE_WAIT_MS = 3_000L
    }
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
    sdpBusy: Boolean = this.sdpBusy,
    sdpNote: String? = this.sdpNote,
    tryStandardProfiles: Boolean = this.tryStandardProfiles,
    transport: TransportChoice = this.transport,
    useHandshake: Boolean = this.useHandshake,
    secureSocket: Boolean = this.secureSocket,
    probeBusy: Boolean = this.probeBusy,
    probeResult: List<String> = this.probeResult,
    gattServices: List<GattService> = this.gattServices,
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
    sdpBusy = sdpBusy,
    sdpNote = sdpNote,
    tryStandardProfiles = tryStandardProfiles,
    transport = transport,
    useHandshake = useHandshake,
    secureSocket = secureSocket,
    probeBusy = probeBusy,
    probeResult = probeResult,
    gattServices = gattServices,
    message = message,
)
