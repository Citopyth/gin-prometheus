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
import de.privat.boom2local.app.TransportChoice
import de.privat.boom2local.app.UiState
import de.privat.boom2local.protocol.GattSelector

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
        val loaded = state.loaded

        SectionCard("Profil", loaded?.source) {
            if (profile == null) {
                Note(loaded?.error ?: "Kein Profil geladen.", warning = true)
            } else {
                KeyValue("Geraet", profile.displayName)
                KeyValue("Modell", profile.model ?: "unbekannt")
                KeyValue("Erlaubte Befehle", profile.commands.size.toString())
                KeyValue("Gesperrte Befehle", profile.blockedCommands.size.toString())
                val problems = loaded?.problems ?: emptyList()
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
                                Tag("passt zum Profil")
                            } else {
                                Tag("anderes Geraet", warning = true)
                            }
                            when {
                                device.customUuids.isNotEmpty() ->
                                    Tag("${device.customUuids.size} eigene Dienste")

                                device.allUuids.isEmpty() ->
                                    Tag("keine Dienste bekannt", warning = true)

                                else -> Tag("nur Standardprofile", warning = true)
                            }
                        }
                        if (selected) {
                            if (device.allUuids.isEmpty()) {
                                Note(
                                    "Android hat zu diesem Geraet keine Dienstliste " +
                                        "gespeichert. \"SDP neu abfragen\" unten holt sie " +
                                        "direkt von der Box.",
                                )
                            } else {
                                Note("Dienste laut SDP (* = herstellerspezifisch):")
                                for (uuid in device.allUuids) {
                                    Mono(
                                        if (uuid in device.customUuids) "* $uuid" else "  $uuid",
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
                for (choice in TransportChoice.entries) {
                    FilterChip(
                        selected = state.transport == choice,
                        onClick = { model.setTransport(choice) },
                        label = {
                            Text(
                                when (choice) {
                                    TransportChoice.AUTO -> "automatisch"
                                    TransportChoice.RFCOMM -> "RFCOMM"
                                    TransportChoice.BLE -> "BLE"
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
            "Profil bearbeiten",
            "Reverse-engineerte Offsets korrigierst du hier, ohne die App neu zu bauen.",
        ) {
            KeyValue("Eigenes Profil aktiv", if (state.hasOverride) "ja" else "nein")
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(model.shippedProfileText()))
                    model.note("Mitgeliefertes Profil in die Zwischenablage kopiert.")
                },
            ) {
                Text("Mitgeliefertes Profil kopieren")
            }
            OutlinedTextField(
                value = profileText,
                onValueChange = { profileText = it },
                label = { Text("Profil-JSON einfuegen") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { model.importProfile(profileText) },
                    enabled = profileText.isNotBlank(),
                ) {
                    Text("Uebernehmen")
                }
                OutlinedButton(
                    onClick = {
                        profileText = ""
                        model.resetProfile()
                    },
                ) {
                    Text("Zuruecksetzen")
                }
            }
        }
    }
}
