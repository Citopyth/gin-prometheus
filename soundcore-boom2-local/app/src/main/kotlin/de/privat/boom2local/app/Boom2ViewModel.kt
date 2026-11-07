package de.privat.boom2local.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.privat.boom2local.app.bt.ControlTransport
import de.privat.boom2local.app.bt.DeviceFinder
import de.privat.boom2local.app.bt.GattTransport
import de.privat.boom2local.app.bt.MediaRemote
import de.privat.boom2local.app.bt.RfcommTransport
import de.privat.boom2local.app.bt.FoundDevice
import de.privat.boom2local.protocol.AuditEntry
import de.privat.boom2local.protocol.AuditKind
import de.privat.boom2local.protocol.AuditLog
import de.privat.boom2local.protocol.BluetoothServices
import de.privat.boom2local.protocol.CaptureReport
import de.privat.boom2local.protocol.CaptureValidator
import de.privat.boom2local.protocol.ChangeOrigin
import de.privat.boom2local.protocol.Command
import de.privat.boom2local.protocol.CommandKind
import de.privat.boom2local.protocol.CommandPolicy
import de.privat.boom2local.protocol.CommandSpec
import de.privat.boom2local.protocol.ConnectionPlan
import de.privat.boom2local.protocol.Decibels
import de.privat.boom2local.protocol.DecodedField
import de.privat.boom2local.protocol.DecodedState
import de.privat.boom2local.protocol.DeviceGate
import de.privat.boom2local.protocol.DeviceProfile
import de.privat.boom2local.protocol.ForeignChange
import de.privat.boom2local.protocol.Frame
import de.privat.boom2local.protocol.GateDecision
import de.privat.boom2local.protocol.GattService
import de.privat.boom2local.protocol.Hex
import de.privat.boom2local.protocol.Inbound
import de.privat.boom2local.protocol.InboundClassifier
import de.privat.boom2local.protocol.LengthSemantics
import de.privat.boom2local.protocol.LinkLiveness
import de.privat.boom2local.protocol.Liveness
import de.privat.boom2local.protocol.LivenessPolicy
import de.privat.boom2local.protocol.PacketCodec
import de.privat.boom2local.protocol.PendingCommand
import de.privat.boom2local.protocol.PendingCommands
import de.privat.boom2local.protocol.PolicyDecision
import de.privat.boom2local.protocol.ProfileEntry
import de.privat.boom2local.protocol.ProvenServices
import de.privat.boom2local.protocol.RawMode
import de.privat.boom2local.protocol.ReconnectPolicy
import de.privat.boom2local.protocol.SessionEvent
import de.privat.boom2local.protocol.SessionMachine
import de.privat.boom2local.protocol.SessionPhase
import de.privat.boom2local.protocol.SecurityPreference
import de.privat.boom2local.protocol.SessionState
import de.privat.boom2local.protocol.SilenceAction
import de.privat.boom2local.protocol.SilenceResponse
import de.privat.boom2local.protocol.SoundHistory
import de.privat.boom2local.protocol.SoundSetting
import de.privat.boom2local.protocol.TamperWatch
import de.privat.boom2local.protocol.TransportKind
import de.privat.boom2local.protocol.TransportPreference
import de.privat.boom2local.protocol.UndoPlan
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
    /** Commands sent and not yet answered. */
    val pending: PendingCommands = PendingCommands(),
    /** Set when the speaker stopped answering without dropping the link. */
    val stalled: Boolean = false,
    val readOnly: Boolean = true,
    val rawMode: RawMode = RawMode.OFF,
    /** Pending argument values per command key, as the controls are moved. */
    val arguments: Map<String, Map<String, Int>> = emptyMap(),
    /**
     * Sound changes this app applied, oldest first, so each can be taken back.
     *
     * Session-scoped and never written to disk: it describes what happened on this
     * connection, and claiming to know the speaker's state across restarts would be a
     * claim nobody read. See [SoundHistory].
     */
    val soundHistory: List<SoundSetting> = emptyList(),
    /** The phone's media volume, read when it is shown or changed. */
    val mediaVolume: Int? = null,
    val mediaVolumeMax: Int? = null,
    /**
     * Changes the speaker reported that this app did not cause.
     *
     * Not an attack log, and the UI says so: the buttons on the speaker land here, so
     * does the phone's volume rocker, so does the speaker turning itself down. What it
     * is, is a timestamped record of everything other than this app that touched it.
     */
    val foreignChanges: List<ForeignChange> = emptyList(),
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
    private val media = MediaRemote(application)

    private val appContext: Context = application.applicationContext
    private var link: ControlTransport? = null
    private var frameJob: Job? = null
    private var retryJob: Job? = null
    private var watchdogJob: Job? = null

    /** Which service each speaker has answered over, loaded once and kept in step. */
    private var provenServices: Map<String, String> = pins.readProvenServices()

    /**
     * Services that came up this session and then said nothing.
     *
     * Session-scoped on purpose: a service can be mute because it is the wrong one, but
     * also because the speaker was busy, so the verdict is never written to disk. It is
     * cleared whenever the user starts a connection by hand.
     */
    private val deadServices = linkedSetOf<String>()

    // What the live link has done so far, for [LinkLiveness]. Plain fields rather than
    // UiState: nothing renders them, and they change on every single frame.
    private var linkServiceUuid: String? = null
    private var linkAddress: String? = null
    private var linkConnectedAtMs: Long = 0
    private var linkFramesIn: Int = 0
    private var linkFramesOut: Int = 0

    /**
     * Watchdog ticks since the last inbound frame.
     *
     * Deliberately not derived from the clock: Android freezes a sleeping app, and this
     * counter freezes with it, which is what keeps a nap from being mistaken for a mute
     * service. See [LinkLiveness].
     */
    private var silentChecks: Int = 0

    /** Set while a mute service is being dropped, so it cannot be dropped twice. */
    private var droppingMuteService = false

    /**
     * When this app last did something that could change what the speaker reports.
     *
     * Any write counts, and so does moving the phone's media volume, because that
     * reaches the speaker through AVRCP and comes back as a volume report that looks
     * exactly like someone else's doing. See [TamperWatch].
     */
    private var lastOwnChangeAtMs: Long? = null

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
        refreshMediaVolume()
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

    fun connect() {
        // A connection the user asked for starts from a clean slate: whatever was mute
        // ten minutes ago may well answer now.
        deadServices.clear()
        droppingMuteService = false
        startConnect(isRetry = false)
    }

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
            provenUuid = ProvenServices.proven(provenServices, device.address),
            excluded = deadServices,
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
                    // Off no longer means "weak", it means "strongest that works".
                    security = if (state.secureSocket) {
                        SecurityPreference.FORCE_AUTHENTICATED
                    } else {
                        SecurityPreference.AUTO
                    },
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
                    // Zeroed before connecting, not after: the read loop starts inside
                    // connect(), so a frame could arrive and then be counted away.
                    linkFramesIn = 0
                    linkFramesOut = 0
                    silentChecks = 0
                    val target = transport.connect(device.device)
                    linkServiceUuid = target.serviceUuid
                    // Pinned here, not read back later: the user can change the
                    // selection while a link is up, and this record must stay about
                    // the speaker the link was actually made to.
                    linkAddress = device.address
                    linkConnectedAtMs = now()
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
                    // The socket being up does not mean the control service is
                    // listening: in four captures the first frame went out within 2 ms
                    // and was ignored half the time.
                    delay(profile.transport.settleAfterConnectMs.coerceIn(0L, 10_000L))
                    startWatchdog()
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

    /**
     * Checks for silence on a timer as well as on every send.
     *
     * Without it the last command of a session would never be reported as unanswered:
     * the checks hang off sending, and the moment that matters is the one after you
     * stop.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                checkForSilence()
                checkForLiveness()
            }
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
        watchdogJob?.cancel()
        watchdogJob = null
        linkServiceUuid = null
        linkAddress = null
        linkFramesIn = 0
        linkFramesOut = 0
        silentChecks = 0
        _ui.update { it.copyWith(pending = it.pending.cleared(), stalled = false) }
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
                    transmit(active, Command.parse(step.command), Hex.decode(step.payload))
                    kotlinx.coroutines.delay(step.delayMsAfter.coerceIn(0L, 5_000L))
                }
            }
        }
        _ui.update { it.copyWith(audit = audit.snapshot()) }
    }

    private fun onFrame(frame: Frame) {
        val profile = _ui.value.profile ?: return
        linkFramesIn++
        silentChecks = 0
        if (linkFramesIn == 1) rememberWorkingService()
        // Anything coming back for a command answers it, whatever kind of answer it is.
        _ui.update {
            it.copyWith(pending = it.pending.answered(frame.command), stalled = false)
        }
        when (val inbound = InboundClassifier.classify(profile, frame)) {
            is Inbound.State -> _ui.update {
                it.copyWith(deviceState = inbound.decoded, audit = audit.snapshot())
            }

            is Inbound.Notification -> {
                val moment = now()
                audit.add(
                    AuditKind.RECEIVED,
                    "${frame.command} meldet ${inbound.field.label}: ${inbound.field.value}",
                    hex = Hex.encode(frame.body),
                    timestampMs = moment,
                )
                // Blocking foreign access is impossible from a phone. Noticing it is
                // not: the speaker announces these three on its own, so subtracting
                // what this app asked for leaves everything else that touched it.
                val origin = TamperWatch.classify(
                    field = inbound.field,
                    previous = _ui.value.live[inbound.field.key]?.value,
                    lastOwnChangeAtMs = lastOwnChangeAtMs,
                    nowMs = moment,
                )
                if (origin is ChangeOrigin.Foreign) {
                    val change = origin.change
                    audit.add(
                        AuditKind.BLOCKED,
                        "Fremde Aenderung: ${change.label} " +
                            "${change.from ?: "?"} -> ${change.to} " +
                            "(nicht von dieser App)",
                        rule = "fremde Aenderung",
                        timestampMs = moment,
                    )
                }
                _ui.update {
                    it.copyWith(
                        live = it.live + (inbound.field.key to inbound.field),
                        foreignChanges = if (origin is ChangeOrigin.Foreign) {
                            TamperWatch.record(it.foreignChanges, origin.change)
                        } else {
                            it.foreignChanges
                        },
                        audit = audit.snapshot(),
                    )
                }
            }

            is Inbound.Acknowledgement -> {
                // The speaker echoes a command back with an empty body to say it
                // understood it. Worth showing: its absence is how you find out a
                // command is not supported.
                audit.add(
                    AuditKind.RECEIVED,
                    "${inbound.command} bestaetigt" +
                        (inbound.label?.let { " ($it)" } ?: ""),
                    timestampMs = now(),
                )
                _ui.update { it.copyWith(audit = audit.snapshot()) }
            }

            is Inbound.Unknown -> {
                audit.add(
                    AuditKind.INFO,
                    "Antwort ${inbound.command}, ${inbound.body.size} Byte " +
                        "(im Profil nicht beschrieben)",
                    hex = Hex.encode(inbound.body),
                    timestampMs = now(),
                )
                _ui.update { it.copyWith(audit = audit.snapshot()) }
            }
        }
    }

    /**
     * Notices that the speaker went quiet.
     *
     * A Soundcore speaker can stop answering while the socket stays open, and then
     * every further command looks like it was sent. Checking on each send and each
     * answer is enough: twelve unanswered frames in a row stop looking like success.
     */
    private fun checkForSilence() {
        val moment = now()
        val overdue = _ui.value.pending.overdue(moment)
        if (overdue.isEmpty()) return
        _ui.update { it.copyWith(pending = it.pending.withoutOverdue(moment)) }

        var gaveUp = false
        for (action in SilenceResponse.decide(overdue)) {
            when (action) {
                is SilenceAction.Resend -> {
                    val waited = (moment - action.pending.sentAtMs) / 1000
                    audit.add(
                        AuditKind.INFO,
                        "Keine Antwort auf ${action.pending.command} nach $waited Sekunden " +
                            "- wird erneut gesendet (Versuch ${action.pending.attempt + 1})",
                        timestampMs = moment,
                    )
                    resend(action.pending)
                }

                is SilenceAction.GiveUp -> {
                    val waited = (moment - action.pending.sentAtMs) / 1000
                    audit.add(
                        AuditKind.ERROR,
                        "Keine Antwort auf ${action.pending.command} nach $waited Sekunden",
                        timestampMs = moment,
                    )
                    gaveUp = true
                }
            }
        }
        _ui.update { it.copyWith(stalled = it.stalled || gaveUp, audit = audit.snapshot()) }
    }

    /**
     * Sends a read again that went unanswered.
     *
     * No policy check: this is the identical frame a moment ago, which the policy
     * already let through, and [SilenceResponse] only ever offers reads.
     */
    /**
     * Records the service the speaker just spoke over, so the next connect starts there.
     *
     * The Boom 2 advertises two services under the same vendor prefix. One carries the
     * control protocol; the other accepts the socket and then never answers, and the
     * order Android hands them over in is not fixed. One captured session connected to
     * the mute one and sat there for three minutes reporting a healthy link.
     *
     * An arriving frame is the only hard evidence of which is which, so this is called
     * exactly once per link, on the first one.
     */
    private fun rememberWorkingService() {
        val uuid = linkServiceUuid ?: return
        val address = linkAddress ?: return
        val next = ProvenServices.remember(provenServices, address, uuid)
        if (next === provenServices) return
        provenServices = next
        pins.writeProvenServices(next)
        audit.add(
            AuditKind.INFO,
            "Dienst $uuid antwortet - wird fuer diese Box gemerkt",
            timestampMs = now(),
        )
        _ui.update { it.copyWith(audit = audit.snapshot()) }
    }

    /**
     * Drops a link that came up and then stayed mute, and tries the next service.
     *
     * A connected socket proves only that something accepted it, and this speaker has a
     * service that does exactly that and nothing else. The rule is in [LinkLiveness];
     * all that happens here is noting the service as useless for this session and
     * starting over, which now skips it.
     */
    private fun checkForLiveness() {
        if (droppingMuteService) return
        if (_ui.value.session.phase != SessionPhase.CONNECTED) return
        silentChecks++
        val verdict = LinkLiveness.verdict(
            policy = LivenessPolicy(),
            connectedAtMs = linkConnectedAtMs,
            framesReceived = linkFramesIn,
            framesSent = linkFramesOut,
            checksWhileSilent = silentChecks,
            nowMs = now(),
        )
        if (verdict !is Liveness.Dead) return

        // The teardown cancels the watchdog, which is the very job this check runs in,
        // so it is handed to the view model's own scope instead of relying on nothing
        // below here ever suspending. The flag keeps the next tick from queueing a
        // second drop in the gap.
        droppingMuteService = true
        viewModelScope.launch { dropMuteService(verdict.reason) }
    }

    private fun dropMuteService(reason: String) {
        val uuid = linkServiceUuid?.lowercase()
        val fresh = uuid != null && uuid !in deadServices
        audit.add(
            AuditKind.ERROR,
            buildString {
                append(reason)
                if (uuid != null) append(" Betroffen: $uuid.")
                if (fresh) append(" Wird uebersprungen, naechster Dienst folgt.")
            },
            timestampMs = now(),
        )
        val lostFrom = _ui.value.session.transport
        if (uuid != null) deadServices += uuid
        closeLink()
        _ui.update { it.copyWith(audit = audit.snapshot()) }
        droppingMuteService = false
        if (fresh) {
            // Not a dropped link but a deliberate change of plan, so the session goes
            // back to idle rather than into the retry counter: the next attempt is a
            // first attempt against a different service.
            emit(SessionEvent.UserDisconnected)
            startConnect(isRetry = false)
        } else {
            // Nothing left to switch to - BLE, or a service already written off. Report
            // it as a lost link and let the reconnect policy decide.
            emit(SessionEvent.LinkLost(lostFrom ?: "", reason))
        }
    }

    /**
     * Every outbound frame goes through here.
     *
     * Not decoration: [checkForLiveness] only condemns a silent link once something was
     * actually asked of it, so a send that bypassed the count would let a mute service
     * look merely idle.
     */
    private suspend fun transmit(active: ControlTransport, command: Command, payload: ByteArray) {
        linkFramesOut++
        active.send(command, payload)
    }

    private suspend fun transmitFrame(active: ControlTransport, raw: ByteArray) {
        linkFramesOut++
        active.sendFrame(raw)
    }

    /**
     * Asks the speaker which devices it still remembers.
     *
     * Read-only, and unproven: Q4 reports 0b:01 and 0b:02 as pairing-list queries on
     * the Space 2, and this speaker has never answered either of them in any capture.
     * It is offered anyway because the question - who else may use this box - has no
     * other answer from here, and a query that goes unanswered costs nothing.
     *
     * There is no known command that *removes* an entry. No source describes one, so
     * emptying the list means resetting the speaker. That is the state of public
     * knowledge, not a shortcoming of this app, and guessing a code that edits a
     * pairing list is how you unpair yourself.
     */
    fun readPairedDevices() {
        for (key in listOf("pairedDevices1", "pairedDevices2")) {
            specFor(key)?.let { send(it) }
        }
    }

    // --- universal remote -------------------------------------------------------------

    /**
     * The one part of this app that works with any Bluetooth speaker.
     *
     * Play, pause, skip and volume are standardised; the rest of this app exists
     * because lights and equalisers are not. See [MediaRemote] for why none of this is
     * sent to the speaker directly.
     */
    fun mediaPlayPause() = pressMedia("Play/Pause") { media.playPause() }
    fun mediaNext() = pressMedia("Naechster Titel") { media.next() }
    fun mediaPrevious() = pressMedia("Voriger Titel") { media.previous() }

    fun mediaVolumeUp() = changeVolume("lauter") { media.volumeUp() }
    fun mediaVolumeDown() = changeVolume("leiser") { media.volumeDown() }

    /**
     * Jumps straight to a volume.
     *
     * One log entry, not seventeen. A capture of real use was 65 lines of "lauter" and
     * "leiser" in bursts of fifteen to seventeen, because the plus and minus buttons
     * move one step each and that is how many steps there are. Dragging a slider has to
     * cost one line at the end, or the log that is supposed to show what happened to the
     * speaker fills up with someone adjusting the volume.
     */
    fun setMediaVolume(value: Int) {
        if (!media.setVolume(value)) {
            note(
                "Android hat die Lautstaerke abgelehnt. Das passiert bei " +
                    "eingeschaltetem \"Nicht stoeren\" und auf Geraeten mit fester " +
                    "Lautstaerke; dafuer braeuchte die App Zugriff auf die " +
                    "Benachrichtigungs-Richtlinie, und den hat sie nicht.",
            )
            refreshMediaVolume()
            return
        }
        lastOwnChangeAtMs = now()
        // Not called "now": that would shadow the clock this very call needs.
        val reached = media.volume()
        val max = media.volumeMax()
        audit.add(
            AuditKind.INFO,
            "Medien-Lautstaerke auf $reached" + (max?.let { " von $it" } ?: ""),
            timestampMs = now(),
        )
        refreshMediaVolume()
    }

    private fun pressMedia(what: String, action: () -> Boolean) {
        if (!action()) {
            note("Medientaste konnte nicht gesendet werden.")
            return
        }
        audit.add(AuditKind.INFO, "Medientaste: $what (ans Handy, nicht an die Box)", timestampMs = now())
        _ui.update { it.copyWith(audit = audit.snapshot()) }
    }

    private fun changeVolume(what: String, action: () -> Boolean) {
        if (!action()) {
            note(
                "Android hat die Lautstaerke abgelehnt. Das passiert bei " +
                    "eingeschaltetem \"Nicht stoeren\"; dafuer braeuchte die App " +
                    "Zugriff auf die Benachrichtigungs-Richtlinie, und den hat sie nicht.",
            )
            return
        }
        lastOwnChangeAtMs = now()
        audit.add(AuditKind.INFO, "Medien-Lautstaerke $what", timestampMs = now())
        refreshMediaVolume()
    }

    /** Reads the phone's media volume into the state so the screen can show it. */
    fun refreshMediaVolume() {
        _ui.update {
            it.copyWith(
                mediaVolume = media.volume(),
                mediaVolumeMax = media.volumeMax(),
                audit = audit.snapshot(),
            )
        }
    }

    // --- sound ------------------------------------------------------------------------

    /**
     * Applies one of the speaker's own equaliser presets.
     *
     * Which index means which sound is unknown - the speaker never says. That is what
     * the ear is for: tap one, listen, tap the next. Every index replaces the previous
     * one and the undo button is one tap away, so nothing here is a one-way door. The
     * one index that was a one-way door, 255, the profile no longer allows.
     */
    fun applyPreset(index: Int) = applySound(
        commandKey = "eqPreset",
        label = "Voreinstellung $index",
        arguments = mapOf("preset" to index),
    )

    /** Moves every equaliser band to the same gain, in whole decibels. */
    fun applyAllBands(raw: Int) = applySound(
        commandKey = "eqAllBands",
        label = "Alle Baender ${Decibels.format(Decibels.fromRaw(raw))}",
        arguments = mapOf("gain" to raw),
    )

    /** Switches BassUp, which the speaker also reports back on its own. */
    fun applyBassUp(on: Boolean) = applySound(
        commandKey = "bassUp",
        label = "BassUp ${if (on) "an" else "aus"}",
        arguments = mapOf("value" to if (on) 1 else 0),
    )

    /**
     * One step back.
     *
     * The rules are in [SoundHistory]; this only looks up the command the plan names
     * and sends it. Note what the floor of that stack is: the neutral curve, not
     * whatever the speaker held before this app first wrote to it. That value was
     * never read, so it cannot be restored, and pretending otherwise would be a lie
     * told at the one moment the user most needs the truth.
     */
    fun undoSound() = replay(SoundHistory.undo(_ui.value.soundHistory), "Rueckgaengig")

    /** Straight back to every band at 0 dB, forgetting the session. */
    fun resetSound() = replay(SoundHistory.reset(), "Zurueck auf neutral")

    private fun applySound(commandKey: String, label: String, arguments: Map<String, Int>) {
        val spec = specFor(commandKey) ?: return
        arguments.forEach { (key, value) -> setArgument(spec.key, key, value) }
        audit.add(AuditKind.INFO, "Klang: $label", timestampMs = now())
        send(spec, SoundHistory.push(_ui.value.soundHistory, SoundSetting(label, commandKey, arguments)))
    }

    private fun replay(plan: UndoPlan, why: String) {
        val spec = specFor(plan.reapply.commandKey) ?: return
        plan.reapply.arguments.forEach { (key, value) -> setArgument(spec.key, key, value) }
        audit.add(
            AuditKind.INFO,
            "$why: ${plan.reapply.label}" + if (plan.atNeutral) " (neutral)" else "",
            timestampMs = now(),
        )
        send(spec, plan.remaining)
    }

    private fun specFor(commandKey: String): CommandSpec? {
        val profile = _ui.value.profile
        if (profile == null) {
            note("Kein brauchbares Profil geladen.")
            return null
        }
        val spec = profile.commands.firstOrNull { it.key == commandKey }
        if (spec == null) {
            note("Das Profil kennt den Befehl '$commandKey' nicht.")
        }
        return spec
    }

    private fun resend(entry: PendingCommand) {
        val active = link ?: return
        if (!active.isConnected) return
        viewModelScope.launch {
            try {
                val payload = Hex.decode(entry.payloadHex)
                transmit(active, entry.command, payload)
                _ui.update {
                    it.copyWith(
                        pending = it.pending.sent(
                            command = entry.command,
                            atMs = now(),
                            kind = entry.kind,
                            payloadHex = entry.payloadHex,
                            attempt = entry.attempt + 1,
                        ),
                        audit = audit.snapshot(),
                    )
                }
            } catch (e: Exception) {
                audit.add(
                    AuditKind.ERROR,
                    "Wiederholung von ${entry.command} fehlgeschlagen: ${e.message}",
                    timestampMs = now(),
                )
                _ui.update { it.copyWith(audit = audit.snapshot()) }
            }
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

    fun send(spec: CommandSpec) = send(spec, historyAfterSending = null)

    /**
     * @param historyAfterSending when set, the sound history becomes this once the
     *   frame is away. Set on success rather than on the tap, so a write refused for
     *   being offline does not quietly consume an undo step.
     */
    private fun send(spec: CommandSpec, historyAfterSending: List<SoundSetting>?) {
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
                if (spec.kind == CommandKind.WRITE) lastOwnChangeAtMs = now()
                transmit(active, spec.commandAddress, payload)
                _ui.update {
                    it.copyWith(
                        pending = it.pending.sent(
                            command = spec.commandAddress,
                            atMs = now(),
                            kind = spec.kind,
                            payloadHex = Hex.encode(payload),
                        ),
                        soundHistory = historyAfterSending ?: it.soundHistory,
                        audit = audit.snapshot(),
                    )
                }
                checkForSilence()
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
                        transmit(active, command, payload)
                        _ui.update {
                            it.copyWith(
                                // A hand-entered command counts as a write: its
                                // meaning is unknown, so it is never repeated.
                                pending = it.pending.sent(command, now()),
                                audit = audit.snapshot(),
                            )
                        }
                        checkForSilence()
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
                    transmitFrame(transport, frame)
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
        watchdogJob?.cancel()
        frameJob?.cancel()
        link?.shutdown()
        link = null
        super.onCleared()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** How long to wait for an answer before calling a framing variant dead. */
        const val PROBE_WAIT_MS = 3_000L

        /** How often to look for commands the speaker never answered. */
        const val WATCHDOG_TICK_MS = 2_000L
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
    pending: PendingCommands = this.pending,
    stalled: Boolean = this.stalled,
    readOnly: Boolean = this.readOnly,
    rawMode: RawMode = this.rawMode,
    arguments: Map<String, Map<String, Int>> = this.arguments,
    soundHistory: List<SoundSetting> = this.soundHistory,
    mediaVolume: Int? = this.mediaVolume,
    mediaVolumeMax: Int? = this.mediaVolumeMax,
    foreignChanges: List<ForeignChange> = this.foreignChanges,
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
    pending = pending,
    stalled = stalled,
    readOnly = readOnly,
    rawMode = rawMode,
    arguments = arguments,
    soundHistory = soundHistory,
    mediaVolume = mediaVolume,
    mediaVolumeMax = mediaVolumeMax,
    foreignChanges = foreignChanges,
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
