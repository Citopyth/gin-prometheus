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
import de.privat.boom2local.protocol.BluetoothServices
import de.privat.boom2local.protocol.CaptureReport
import de.privat.boom2local.protocol.CaptureValidator
import de.privat.boom2local.protocol.Command
import de.privat.boom2local.protocol.CommandPolicy
import de.privat.boom2local.protocol.CommandSpec
import de.privat.boom2local.protocol.ConnectionPlan
import de.privat.boom2local.protocol.DecodedField
import de.privat.boom2local.protocol.DecodedState
import de.privat.boom2local.protocol.DeviceGate
import de.privat.boom2local.protocol.DeviceProfile
import de.privat.boom2local.protocol.Frame
import de.privat.boom2local.protocol.GateDecision
import de.privat.boom2local.protocol.GattService
import de.privat.boom2local.protocol.Hex
import de.privat.boom2local.protocol.LengthSemantics
import de.privat.boom2local.protocol.PacketCodec
import de.privat.boom2local.protocol.PolicyDecision
import de.privat.boom2local.protocol.ProfileEntry
import de.privat.boom2local.protocol.ProfileMatcher
import de.privat.boom2local.protocol.RawMode
import de.privat.boom2local.protocol.ReconnectPolicy
import de.privat.boom2local.protocol.SessionEvent
import de.privat.boom2local.protocol.SessionMachine
import de.privat.boom2local.protocol.SessionPhase
import de.privat.boom2local.protocol.SessionState
import de.privat.boom2local.protocol.StateDecoder
import de.privat.boom2local.protocol.TransportKind
import de.privat.boom2local.protocol.TransportPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the screens render. */
class UiState(
    /** Every profile the app knows: shipped ones plus the ones you imported. */
    val library: List<ProfileEntry> = emptyList(),
    /** Set when you picked a profile by hand instead of letting the app choose. */
    val manualProfileId: String? = null,
    val bluetoothSupported: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val selected: FoundDevice? = null,
    val session: SessionState = SessionState(),
    /** Try again by itself after the speaker drops the link. Off by default. */
    val autoReconnect: Boolean = false,
    val deviceState: DecodedState? = null,
    /** Values the speaker announced on its own, newest per field. */
    val live: Map<String, DecodedField> = emptyMap(),
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
    val transport: TransportPreference = TransportPreference.AUTO,
    /** Send the profile's handshake after connecting. Off by default: see the UI note. */
    val useHandshake: Boolean = false,
    /** Use an authenticated RFCOMM socket instead of the profile's insecure one. */
    val secureSocket: Boolean = false,
    /** The speaker this app is bound to, if any. */
    val pinnedAddress: String? = null,
    val probeBusy: Boolean = false,
    val probeResult: List<String> = emptyList(),
    /** GATT services seen on the last BLE attempt, successful or not. */
    val gattServices: List<GattService> = emptyList(),
    val message: String? = null,
) {
    /** Profiles that parsed and validated, in library order. */
    val usableProfiles: List<DeviceProfile>
        get() = library.mapNotNull { if (it.isUsable) it.profile else null }

    /**
     * The profile in force: your manual choice, else the one that recognised the
     * selected speaker.
     */
    val active: ProfileEntry?
        get() {
            manualProfileId?.let { wanted ->
                library.firstOrNull { it.id == wanted }?.let { return it }
            }
            val matched = selected?.match?.profile ?: return null
            return library.firstOrNull { it.profile === matched }
        }

    val profile: DeviceProfile? get() = active?.profile

    val connected: Boolean get() = session.phase == SessionPhase.CONNECTED

    val reconnectPolicy: ReconnectPolicy get() = ReconnectPolicy(enabled = autoReconnect)
}

class Boom2ViewModel(application: Application) : AndroidViewModel(application) {

    private val audit = AuditLog(capacity = 800)
    private val store = ProfileStore(application)
    private val pins = PinStore(application)
    private val finder = DeviceFinder(application)

    private val appContext: Context = application.applicationContext
    private var link: ControlTransport? = null
    private var frameJob: Job? = null
    private var retryJob: Job? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        reloadLibrary()
    }

    // --- profile ----------------------------------------------------------------------

    fun reloadLibrary() {
        val library = store.load()
        _ui.update {
            UiState(
                library = library,
                manualProfileId = it.manualProfileId?.takeIf { wanted ->
                    library.any { entry -> entry.id == wanted }
                },
                bluetoothSupported = finder.bluetoothSupported,
                bluetoothEnabled = finder.bluetoothEnabled,
                permissionGranted = finder.hasConnectPermission(),
                readOnly = it.readOnly,
                rawMode = it.rawMode,
                pinnedAddress = pins.read(),
                transport = it.transport,
                audit = audit.snapshot(),
            )
        }
        library.filter { it.error != null }.forEach { note("${it.source}: ${it.error}") }
        refreshDevices()
        _ui.update { it.copyWith(arguments = argumentDefaults(it.usableProfiles)) }
    }

    /** Pick a profile by hand; null hands the choice back to the app. */
    fun selectProfile(id: String?) {
        audit.add(
            AuditKind.INFO,
            if (id == null) "Profilwahl wieder automatisch" else "Profil von Hand gewaehlt: $id",
            timestampMs = now(),
        )
        _ui.update {
            it.copyWith(manualProfileId = id, audit = audit.snapshot())
                .let { next -> next.copyWith(arguments = argumentDefaults(next.usableProfiles)) }
        }
    }

    fun shippedProfileNames(): List<String> = store.shippedNames()

    fun shippedProfileText(name: String): String = store.shippedText(name)

    fun importProfile(text: String) {
        val result = store.import(text)
        if (!result.isUsable) {
            note("Profil nicht uebernommen: ${result.error ?: result.problems.joinToString("; ")}")
            return
        }
        audit.add(AuditKind.INFO, "Profil '${result.id}' importiert", timestampMs = now())
        reloadLibrary()
        note("Profil '${result.id}' uebernommen.")
    }

    fun removeProfile(entry: ProfileEntry) {
        val fileName = entry.fileName ?: run { note("Mitgelieferte Profile lassen sich nicht loeschen."); return }
        if (!store.remove(fileName)) {
            note("Konnte $fileName nicht loeschen.")
            return
        }
        audit.add(AuditKind.INFO, "Profil $fileName geloescht", timestampMs = now())
        reloadLibrary()
        note("Profil geloescht.")
    }

    private fun argumentDefaults(profiles: List<DeviceProfile>): Map<String, Map<String, Int>> =
        profiles.flatMap { it.commands }.associate { spec ->
            spec.key to spec.arguments.associate { argument ->
                argument.key to (argument.default ?: argument.choices.firstOrNull()?.value ?: argument.min)
            }
        }

    // --- devices ----------------------------------------------------------------------

    fun refreshDevices() {
        val profiles = _ui.value.usableProfiles
        val devices = if (profiles.isEmpty()) emptyList() else finder.pairedDevices(profiles)
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
        val selected = state.selected ?: run { note("Kein Geraet ausgewaehlt."); return }
        _ui.update { it.copyWith(sdpBusy = true, sdpNote = null) }
        viewModelScope.launch {
            val result = finder.refreshSdp(selected.device)
            val updated = finder.describe(selected.device, _ui.value.usableProfiles, result.uuids)
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

    /**
     * Binds the app to the selected speaker.
     *
     * Names are a convenience; two speakers of the same model carry the same one. The
     * address is exact.
     */
    fun pinSelected() {
        val device = _ui.value.selected ?: run { note("Kein Geraet ausgewaehlt."); return }
        pins.write(device.address)
        audit.add(AuditKind.INFO, "App an ${device.address} gebunden", timestampMs = now())
        _ui.update { it.copyWith(pinnedAddress = pins.read(), audit = audit.snapshot()) }
        note("Gebunden an ${device.address}. Andere Geraete werden jetzt abgelehnt.")
    }

    fun unpin() {
        pins.write(null)
        audit.add(AuditKind.INFO, "Geraete-Bindung aufgehoben", timestampMs = now())
        _ui.update { it.copyWith(pinnedAddress = null, audit = audit.snapshot()) }
        note("Bindung aufgehoben.")
    }

    fun setUseHandshake(value: Boolean) {
        _ui.update { it.copyWith(useHandshake = value) }
    }

    fun setSecureSocket(value: Boolean) {
        _ui.update { it.copyWith(secureSocket = value) }
    }

    fun setTransport(choice: TransportPreference) {
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
            appendLine("Gebunden: ${_ui.value.pinnedAddress ?: "nein"}")
            appendLine("Profil: ${_ui.value.active?.profile?.displayName ?: "keines"}")
            appendLine("Dienste laut SDP: ${selected.allUuids.size}")
            for (described in BluetoothServices.describeAll(selected.allUuids)) {
                appendLine("  ${described.uuid}")
                appendLine("    ${described.name}  [${described.risk}]")
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

    fun connect() = startConnect(isRetry = false)

    private fun startConnect(isRetry: Boolean) {
        val state = _ui.value
        val profile = state.profile ?: run { note("Kein brauchbares Profil geladen."); return }
        val device = state.selected ?: run { note("Kein Geraet ausgewaehlt."); return }

        when (
            val gate = DeviceGate.check(
                pinnedAddress = state.pinnedAddress,
                address = device.address,
                name = device.name,
                recognised = device.matchesProfile || state.manualProfileId != null,
            )
        ) {
            is GateDecision.Refused -> {
                audit.add(
                    AuditKind.BLOCKED,
                    "Verbindung zu ${device.address} abgelehnt: ${gate.message}",
                    rule = gate.rule,
                    timestampMs = now(),
                )
                _ui.update { it.copyWith(audit = audit.snapshot()) }
                note(gate.message)
                return
            }

            GateDecision.Allowed -> Unit
        }

        val rfcommCandidates = finder.resolveServiceUuids(
            found = device,
            profile = profile,
            includeStandardProfiles = state.tryStandardProfiles,
        ).map { RfcommTransport.UuidCandidate(it.uuid, it.origin) }

        val order = ConnectionPlan.plan(
            preference = state.transport,
            rfcommCandidates = rfcommCandidates.size,
            bleEnabled = profile.ble.enabled,
        )
        if (order.isEmpty()) {
            note(
                "Kein Transportweg uebrig. " +
                    ConnectionPlan.whyEmpty(rfcommCandidates.size, profile.ble.enabled),
            )
            return
        }
        val plan = order.map { kind ->
            when (kind) {
                TransportKind.RFCOMM -> RfcommTransport(
                    profile = profile,
                    audit = audit,
                    candidates = rfcommCandidates,
                    insecureOverride = if (state.secureSocket) false else null,
                    clock = ::now,
                )

                TransportKind.BLE -> GattTransport(appContext, profile, audit, ::now)
            }
        }

        closeLink()
        if (!isRetry) cancelRetry()

        viewModelScope.launch {
            for (transport in plan) {
                link = transport
                emit(SessionEvent.Attempting(transport.name))
                val collector = viewModelScope.launch {
                    launch { transport.frames.collect { frame -> onFrame(frame) } }
                    launch {
                        transport.disconnects.collect { reason ->
                            // The event names its transport, so a notice from a socket
                            // abandoned earlier cannot disturb a link that is working.
                            emit(SessionEvent.LinkLost(transport.name, reason))
                        }
                    }
                }
                frameJob = collector
                try {
                    val target = transport.connect(device.device)
                    emit(
                        SessionEvent.Established(
                            transport = transport.name,
                            label = target.label,
                            detail = target.detail,
                        ),
                    )
                    _ui.update {
                        it.copyWith(
                            gattServices = gattServicesOf(transport) ?: it.gattServices,
                            audit = audit.snapshot(),
                        )
                    }
                    runHandshake(profile)
                    readState()
                    return@launch
                } catch (e: Exception) {
                    emit(
                        SessionEvent.AttemptFailed(
                            transport = transport.name,
                            reason = e.message ?: e.javaClass.simpleName,
                        ),
                    )
                    collector.cancel()
                    if (frameJob === collector) frameJob = null
                    val seen = gattServicesOf(transport)
                    transport.shutdown()
                    if (link === transport) link = null
                    _ui.update {
                        it.copyWith(gattServices = seen ?: it.gattServices, audit = audit.snapshot())
                    }
                }
            }
            emit(
                SessionEvent.AllAttemptsFailed(
                    hint = if (device.customUuids.isEmpty()) {
                        "Die Box bietet laut SDP keinen herstellerspezifischen Dienst an. " +
                            "Drueck zuerst \"SDP neu abfragen\": die gespeicherte Liste " +
                            "stammt vom Koppeln und ist oft unvollstaendig."
                    } else {
                        null
                    },
                ),
            )
        }
    }

    /**
     * Feeds one event to the session machine and acts on what it decides.
     *
     * The rules themselves live in [SessionMachine], where they are tested; this only
     * starts or cancels the timer the machine asks for.
     */
    private fun emit(event: SessionEvent) {
        val policy = _ui.value.reconnectPolicy
        val before = _ui.value.session
        val after = SessionMachine.next(before, event, policy)
        _ui.update { it.copyWith(session = after, audit = audit.snapshot()) }
        val wait = after.retryInMs
        if (wait == null) {
            cancelRetry()
        } else if (before.retryInMs == null) {
            scheduleRetry(wait)
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        cancelRetry()
        audit.add(
            AuditKind.INFO,
            "Erneuter Verbindungsversuch in ${delayMs / 1000} Sekunden",
            timestampMs = now(),
        )
        retryJob = viewModelScope.launch {
            delay(delayMs)
            emit(SessionEvent.RetryStarted)
            startConnect(isRetry = true)
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    fun setAutoReconnect(value: Boolean) {
        audit.add(
            AuditKind.INFO,
            if (value) "Automatisch wiederverbinden: an" else "Automatisch wiederverbinden: aus",
            timestampMs = now(),
        )
        if (!value) cancelRetry()
        _ui.update { it.copyWith(autoReconnect = value, audit = audit.snapshot()) }
    }

    private fun gattServicesOf(transport: ControlTransport): List<GattService>? =
        (transport as? GattTransport)?.discoveredServices?.takeIf { it.isNotEmpty() }

    fun disconnect() {
        cancelRetry()
        closeLink()
        emit(SessionEvent.UserDisconnected)
    }

    /** Tears the transport down without telling the session machine anything. */
    private fun closeLink() {
        frameJob?.cancel()
        frameJob = null
        // shutdown(), not close(): a fresh transport is built for every attempt, so this
        // instance is finished with and its coroutine scope has to go with it.
        link?.shutdown()
        link = null
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
            return
        }
        // The speaker announces a changed battery level, volume or BassUp setting by
        // itself, without being asked. Those frames are described in the profile, so
        // they update the display instead of being logged as noise.
        val announced = StateDecoder.decodeNotification(profile, frame)
        if (announced != null) {
            audit.add(
                AuditKind.RECEIVED,
                "${frame.command} meldet ${announced.label}: ${announced.value}",
                hex = Hex.encode(frame.body),
                timestampMs = now(),
            )
            _ui.update {
                it.copyWith(
                    live = it.live + (announced.key to announced),
                    audit = audit.snapshot(),
                )
            }
            return
        }
        audit.add(
            AuditKind.INFO,
            "Antwort ${frame.command}, ${frame.body.size} Byte (im Profil nicht beschrieben)",
            hex = Hex.encode(frame.body),
            timestampMs = now(),
        )
        _ui.update { it.copyWith(audit = audit.snapshot()) }
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
        cancelRetry()
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
    library: List<ProfileEntry> = this.library,
    manualProfileId: String? = this.manualProfileId,
    bluetoothSupported: Boolean = this.bluetoothSupported,
    bluetoothEnabled: Boolean = this.bluetoothEnabled,
    permissionGranted: Boolean = this.permissionGranted,
    devices: List<FoundDevice> = this.devices,
    selected: FoundDevice? = this.selected,
    session: SessionState = this.session,
    autoReconnect: Boolean = this.autoReconnect,
    deviceState: DecodedState? = this.deviceState,
    live: Map<String, DecodedField> = this.live,
    readOnly: Boolean = this.readOnly,
    rawMode: RawMode = this.rawMode,
    arguments: Map<String, Map<String, Int>> = this.arguments,
    audit: List<AuditEntry> = this.audit,
    capture: CaptureReport? = this.capture,
    sdpBusy: Boolean = this.sdpBusy,
    sdpNote: String? = this.sdpNote,
    tryStandardProfiles: Boolean = this.tryStandardProfiles,
    transport: TransportPreference = this.transport,
    useHandshake: Boolean = this.useHandshake,
    secureSocket: Boolean = this.secureSocket,
    pinnedAddress: String? = this.pinnedAddress,
    probeBusy: Boolean = this.probeBusy,
    probeResult: List<String> = this.probeResult,
    gattServices: List<GattService> = this.gattServices,
    message: String? = this.message,
): UiState = UiState(
    library = library,
    manualProfileId = manualProfileId,
    bluetoothSupported = bluetoothSupported,
    bluetoothEnabled = bluetoothEnabled,
    permissionGranted = permissionGranted,
    devices = devices,
    selected = selected,
    session = session,
    autoReconnect = autoReconnect,
    deviceState = deviceState,
    live = live,
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
    pinnedAddress = pinnedAddress,
    probeBusy = probeBusy,
    probeResult = probeResult,
    gattServices = gattServices,
    message = message,
)
