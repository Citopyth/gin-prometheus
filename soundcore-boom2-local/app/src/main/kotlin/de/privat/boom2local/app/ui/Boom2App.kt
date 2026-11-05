package de.privat.boom2local.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.privat.boom2local.app.Boom2ViewModel
import de.privat.boom2local.app.LinkState
import de.privat.boom2local.app.SelfCheck
import de.privat.boom2local.app.UiState
import de.privat.boom2local.protocol.BluetoothServices
import de.privat.boom2local.protocol.CommandKind
import de.privat.boom2local.protocol.GattSelector
import de.privat.boom2local.protocol.TransportPreference

private val TabTitles = listOf("Geraet", "Steuern", "Protokoll", "Sicherheit")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Boom2App(model: Boom2ViewModel) {
    val state by model.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        model.onPermissionResult(granted)
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbar.showSnackbar(message)
            model.note(null)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Boom2 Local") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(modifier = Modifier.padding(insets).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            when (tab) {
                0 -> DeviceTab(state, model) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }

                1 -> ControlTab(state, model)
                2 -> ProtocolTab(state, model)
                else -> SecurityTab(state, model)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceTab(
    state: UiState,
    model: Boom2ViewModel,
    requestPermission: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val profile = state.profile
        val active = state.active

        SectionCard(
            "Profil",
            active?.source ?: "${state.library.size} Profile in der Bibliothek",
        ) {
            if (profile == null) {
                Note(
                    if (state.selected == null) {
                        "Waehle unten ein Geraet - die App sucht dann selbst das passende " +
                            "Profil heraus."
                    } else {
                        "Kein Profil erkennt dieses Geraet. Im Reiter Sicherheit kannst du " +
                            "eines von Hand waehlen oder ein eigenes importieren."
                    },
                    warning = state.selected != null,
                )
            } else {
                KeyValue("Geraet", profile.displayName)
                KeyValue("Modell", profile.model ?: "unbekannt")
                KeyValue("Erlaubte Befehle", profile.commands.size.toString())
                KeyValue(
                    "Davon schreibend",
                    profile.commands.count { it.kind == CommandKind.WRITE }.toString(),
                )
                val how = state.manualProfileId?.let { "von Hand gewaehlt" }
                    ?: state.selected?.match?.reason?.let { "erkannt an: $it" }
                if (how != null) Note(how)
                if (profile.commands.none { it.kind == CommandKind.WRITE }) {
                    Note(
                        "Dieses Profil enthaelt keinen Schreibbefehl. Die App kann damit " +
                            "nur lesen und zuhoeren.",
                    )
                }
                val problems = active?.problems ?: emptyList()
                if (problems.isNotEmpty()) {
                    HorizontalDivider()
                    Note("Profil-Fehler:", warning = true)
                    for (problem in problems) Note("- $problem", warning = true)
                }
            }
        }

        SectionCard(
            "Bluetooth",
            "Die App sucht nicht, sie liest nur die bereits gekoppelten Geraete. " +
                "Deshalb braucht sie keine Standort-Berechtigung.",
        ) {
            if (!state.bluetoothSupported) {
                Note("Dieses Geraet hat keinen Bluetooth-Adapter.", warning = true)
            } else {
                KeyValue("Bluetooth", if (state.bluetoothEnabled) "an" else "aus")
                KeyValue(
                    "Berechtigung",
                    if (state.permissionGranted) "erteilt" else "fehlt",
                )
                if (!state.permissionGranted) {
                    Button(onClick = requestPermission) {
                        Text("Bluetooth-Berechtigung erteilen")
                    }
                }
                OutlinedButton(onClick = { model.refreshDevices() }) {
                    Text("Gekoppelte Geraete neu laden")
                }
            }
        }

        SectionCard(
            "Gekoppelte Geraete",
            if (state.devices.isEmpty()) {
                "Keine gefunden. Die Boom 2 muss in den Bluetooth-Einstellungen gekoppelt sein."
            } else {
                "${state.devices.size} gefunden"
            },
        ) {
            for (device in state.devices) {
                val selected = state.selected?.address == device.address
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { model.select(device) },
                    colors = if (selected) {
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Mono(device.address)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (device.matchesProfile) {
                                Tag("Profil vorhanden")
                            } else {
                                Tag("kein Profil", warning = true)
                            }
                            when {
                                device.customUuids.isNotEmpty() ->
                                    Tag("${device.customUuids.size} eigene Dienste")

                                device.allUuids.isEmpty() ->
                                    Tag("keine Dienste bekannt", warning = true)

                                else -> Tag("nur Standardprofile", warning = true)
                            }
                        }
                        device.match?.let { matched ->
                            Note("Profil: ${matched.profile.displayName} (${matched.reason})")
                        }
                        if (selected) {
                            if (device.allUuids.isEmpty()) {
                                Note(
                                    "Android hat zu diesem Geraet keine Dienstliste " +
                                        "gespeichert. \"SDP neu abfragen\" unten holt sie " +
                                        "direkt von der Box.",
                                )
                            } else {
                                Note("Dienste, die diese Box anbietet:")
                                for (service in BluetoothServices.describeAll(device.allUuids)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            service.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        RiskTag(service.risk)
                                    }
                                    Mono(service.uuid)
                                }
                                val concerns =
                                    BluetoothServices.concerning(device.allUuids)
                                if (concerns.isNotEmpty()) {
                                    HorizontalDivider()
                                    Note(
                                        "Davon ${concerns.size} mit erweiterter Reichweite:",
                                        warning = true,
                                    )
                                    for (service in concerns) {
                                        Note("- ${service.name}: ${service.meaning}", warning = true)
                                    }
                                    Note(
                                        "Diese Dienste gehoeren der Box und stehen in ihrer " +
                                            "Firmware. Keine App kann sie dort loeschen. " +
                                            "Abschalten laesst sich nur, was dein Handy davon " +
                                            "benutzt - siehe Reiter Sicherheit.",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionCard("Verbindung") {
            when (val link = state.link) {
                LinkState.Idle -> Note("Nicht verbunden.")
                LinkState.Connecting -> Note("Verbinde ...")
                is LinkState.Connected -> {
                    Note("Verbunden.")
                    Mono(link.uuid)
                    Note(link.origin)
                }

                is LinkState.Failed -> Note(link.reason, warning = true)
            }
            if (state.gattServices.isNotEmpty()) {
                HorizontalDivider()
                Note(
                    "GATT-Dienste der Box (${state.gattServices.size}, " +
                        "* = herstellerspezifisch):",
                )
                for (service in state.gattServices) {
                    Mono(
                        (if (GattSelector.isCustom(service.uuid)) "* " else "  ") + service.uuid,
                    )
                    for (characteristic in service.characteristics) {
                        val flags = buildList {
                            if (characteristic.write) add("schreiben")
                            if (characteristic.writeNoResponse) add("schreiben-ohne-Antwort")
                            if (characteristic.notify) add("meldet")
                            if (characteristic.indicate) add("meldet-bestaetigt")
                        }
                        Mono(
                            "      ${characteristic.uuid}  " +
                                if (flags.isEmpty()) "(nur lesbar)" else flags.joinToString(", "),
                        )
                    }
                }
            }
            Text("Transportweg", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in TransportPreference.entries) {
                    FilterChip(
                        selected = state.transport == choice,
                        onClick = { model.setTransport(choice) },
                        label = {
                            Text(
                                when (choice) {
                                    TransportPreference.AUTO -> "automatisch"
                                    TransportPreference.RFCOMM -> "RFCOMM"
                                    TransportPreference.BLE -> "BLE"
                                },
                            )
                        },
                    )
                }
            }
            Note(
                "Automatisch heisst: erst klassisches RFCOMM, dann BLE. Alle " +
                    "veroeffentlichten Beschreibungen dieses Protokolls betreffen RFCOMM, " +
                    "aber nur Kopfhoerer und die Space 2 - ob dieser Lautsprecher " +
                    "stattdessen BLE spricht, zeigt erst der Versuch.",
            )
            KeyValue("Gebunden an", state.pinnedAddress ?: "nichts")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { model.pinSelected() },
                    enabled = state.selected != null &&
                        state.selected.address != state.pinnedAddress,
                ) {
                    Text("An dieses Geraet binden")
                }
                OutlinedButton(
                    onClick = { model.unpin() },
                    enabled = state.pinnedAddress != null,
                ) {
                    Text("Loesen")
                }
            }
            Note(
                "Gebunden heisst: die App spricht ausschliesslich mit dieser Adresse und " +
                    "lehnt jedes andere Geraet ab, auch eine zweite Boom 2 mit demselben " +
                    "Namen. Das bindet die App an die Box - nicht die Box an dein Handy. " +
                    "Wen die Box akzeptiert, entscheidet ihre eigene Firmware.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { model.refreshSdp() },
                    enabled = state.selected != null && !state.sdpBusy,
                ) {
                    Text(if (state.sdpBusy) "Frage ab ..." else "SDP neu abfragen")
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(model.sdpExport()))
                        model.note("Dienstliste in die Zwischenablage kopiert.")
                    },
                    enabled = state.selected != null,
                ) {
                    Text("Dienste kopieren")
                }
            }
            Note(
                "Die gespeicherte Dienstliste stammt vom Koppeln und ist bei Lautsprechern " +
                    "oft unvollstaendig. Die Abfrage holt sie frisch von der Box, die dazu " +
                    "eingeschaltet und in Reichweite sein muss.",
            )
            state.sdpNote?.let { Note(it, warning = true) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Auch Standardprofile probieren",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = state.tryStandardProfiles,
                    onCheckedChange = { model.setTryStandardProfiles(it) },
                )
            }
            Note(
                "Letzter Ausweg: verbindet versuchsweise auch auf A2DP, AVRCP und die " +
                    "uebrigen Standardprofile. Das stoert die Musikwiedergabe nicht, " +
                    "dauert aber laenger und fuehrt meist zu nichts.",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Handshake senden", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.useHandshake,
                    onCheckedChange = { model.setUseHandshake(it) },
                )
            }
            Note(
                "Die Box nimmt die Verbindung an, antwortet aber nicht und legt nach " +
                    "etwa einer halben Minute auf. Beim Soundcore Space 2 ist genau das " +
                    "das Verhalten, solange der Client sich nicht begruesst hat. Dieser " +
                    "Schalter sendet die dort dokumentierte Begruessung - sieben Pakete, " +
                    "deren Bedeutung fuer die Boom 2 niemand kennt. Deshalb aus by " +
                    "default: es sind Schreibzugriffe ins Unbekannte.",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Verbindung authentifizieren",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = state.secureSocket,
                    onCheckedChange = { model.setSecureSocket(it) },
                )
            }
            Note(
                "Normalerweise wird ein unauthentifizierter Socket benutzt, weil der mit " +
                    "mehr Geraeten funktioniert. Manche Hersteller-Dienste antworten aber " +
                    "nur ueber eine authentifizierte Verbindung - auch das sieht von " +
                    "aussen aus wie eine Box, die schweigt.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { model.connect() },
                    enabled = state.selected != null && state.link !is LinkState.Connected,
                ) {
                    Text("Verbinden")
                }
                OutlinedButton(
                    onClick = { model.disconnect() },
                    enabled = state.link is LinkState.Connected ||
                        state.link is LinkState.Connecting,
                ) {
                    Text("Trennen")
                }
            }
        }
    }
}

@Composable
private fun SecurityTab(state: UiState, model: Boom2ViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val checks = remember { SelfCheck.run(context) }
    var profileText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            "Selbstpruefung",
            "Gelesen aus der installierten App, nicht aus dem Quelltext.",
        ) {
            for (check in checks) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tag(if (check.ok) "ok" else "Problem", warning = !check.ok)
                    Text(check.title, style = MaterialTheme.typography.bodyMedium)
                }
                Note(check.detail, warning = !check.ok)
                HorizontalDivider()
            }
            Note(
                "Ohne die Berechtigung INTERNET kann ein Android-Prozess gar keinen " +
                    "Netzwerk-Socket oeffnen. Das ist keine Einstellung, die sich im " +
                    "Betrieb umdrehen laesst.",
            )
        }

        SectionCard(
            "Mikrofon und Telefonbuch abschalten",
            "Was diese App nicht kann, und wo es wirklich geht.",
        ) {
            Note(
                "Die Dienste einer Box - Freisprechen, Mikrofon, Telefonbuch - stehen in " +
                    "ihrer Firmware. Keine App kann sie dort entfernen, und eine App ohne " +
                    "Systemrechte kann sie auch auf dem Handy nicht abschalten: dafuer " +
                    "braucht es BLUETOOTH_PRIVILEGED, das nur System-Apps bekommen.",
            )
            Note(
                "Abschalten kannst du sie trotzdem - in den Android-Einstellungen, und " +
                    "das wirkt systemweit statt nur in dieser App:",
            )
            Note("1. Einstellungen, dann Verbundene Geraete oder Bluetooth")
            Note("2. Zahnrad neben der Soundcore Boom 2")
            Note("3. \"Anrufe\" ausschalten - das ist der Freisprech- und Mikrofon-Kanal")
            Note("4. \"Kontaktfreigabe\" ausschalten, falls vorhanden")
            Note("5. \"Medienaudio\" anlassen, sonst spielt die Box keine Musik mehr")
            Note(
                "Danach taucht die Box nicht mehr als Freisprecheinrichtung auf und " +
                    "Anrufe laufen ueber das Telefon. Welche Dienste deine Box ueberhaupt " +
                    "anbietet, steht im Reiter Geraet.",
            )
            Note(
                "Wen die Box selbst akzeptiert, laesst sich von aussen gar nicht steuern. " +
                    "Soll sie nur dein Handy kennen: Box auf Werkseinstellungen " +
                    "zuruecksetzen, dann ausschliesslich dein Handy koppeln. Danach ist " +
                    "ihre Kopplungsliste leer bis auf dich.",
            )
        }

        SectionCard("Was gesendet werden darf") {
            for (line in model.policyDescription()) Note(line)
        }

        val profile = state.profile
        if (profile != null && profile.notes.isNotEmpty()) {
            SectionCard("Hinweise aus dem Profil") {
                for (note in profile.notes) Note("- $note")
            }
        }

        SectionCard(
            "Profil-Bibliothek",
            "${state.library.size} Profile. Welches gilt, entscheidet normalerweise die " +
                "App anhand der Dienste, die ein Geraet anbietet.",
        ) {
            Text("Profilwahl", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.manualProfileId == null,
                    onClick = { model.selectProfile(null) },
                    label = { Text("automatisch") },
                )
                for (entry in state.library) {
                    val id = entry.id ?: continue
                    FilterChip(
                        selected = state.manualProfileId == id,
                        onClick = { model.selectProfile(id) },
                        label = { Text(entry.profile?.displayName ?: id) },
                    )
                }
            }
            HorizontalDivider()
            for (entry in state.library) {
                val isActive = state.active === entry
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        entry.profile?.displayName ?: "(unlesbar)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isActive) Tag("aktiv")
                    if (entry.removable) Tag("eigenes")
                }
                Mono(entry.source)
                entry.error?.let { Note(it, warning = true) }
                for (problem in entry.problems) Note("- $problem", warning = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(entry.text.orEmpty()))
                            model.note("${entry.profile?.displayName} kopiert.")
                        },
                        enabled = entry.text != null,
                    ) {
                        Text("Kopieren")
                    }
                    if (entry.removable) {
                        OutlinedButton(onClick = { model.removeProfile(entry) }) {
                            Text("Loeschen")
                        }
                    }
                }
                HorizontalDivider()
            }
            Note(
                "Ein eigenes Profil mit derselben Kennung ueberschreibt das mitgelieferte " +
                    "und ueberlebt damit ein App-Update. Loeschst du es, gilt wieder das " +
                    "mitgelieferte.",
            )
            OutlinedTextField(
                value = profileText,
                onValueChange = { profileText = it },
                label = { Text("Profil-JSON einfuegen") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
            Button(
                onClick = {
                    model.importProfile(profileText)
                    profileText = ""
                },
                enabled = profileText.isNotBlank(),
            ) {
                Text("Hinzufuegen")
            }
            Note(
                "Die App prueft ein Profil, bevor sie es speichert - ein Tippfehler kann " +
                    "sie also nicht lahmlegen.",
            )
        }
    }
}