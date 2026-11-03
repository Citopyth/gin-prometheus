package de.privat.boom2local.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.privat.boom2local.app.Boom2ViewModel
import de.privat.boom2local.app.LinkState
import de.privat.boom2local.app.UiState
import de.privat.boom2local.protocol.AuditEntry
import de.privat.boom2local.protocol.AuditKind
import de.privat.boom2local.protocol.CommandKind
import de.privat.boom2local.protocol.CommandSpec
import de.privat.boom2local.protocol.LengthSemantics
import de.privat.boom2local.protocol.RawMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ControlTab(state: UiState, model: Boom2ViewModel) {
    val profile = state.profile
    val connected = state.link is LinkState.Connected

    if (profile == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            SectionCard("Kein Profil") {
                Note("Ohne brauchbares Profil gibt es nichts zu steuern.", warning = true)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Schutz") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Nur lesen", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.readOnly,
                    onCheckedChange = { model.setReadOnly(it) },
                )
            }
            Note(
                if (state.readOnly) {
                    "Es wird nichts an die Box geschrieben. Zum Aendern von Einstellungen " +
                        "ausschalten."
                } else {
                    "Schreibende Befehle aus dem Profil sind freigegeben."
                },
            )
        }

        SectionCard(
            "Status",
            if (connected) null else "Nicht verbunden - Werte stammen aus der letzten Abfrage.",
        ) {
            Button(onClick = { model.readState() }, enabled = connected) {
                Text("Status lesen")
            }
            val decoded = state.deviceState
            if (decoded == null) {
                Note("Noch nichts gelesen.")
            } else {
                Mono("Antwort ${decoded.command}, ${decoded.bodySize} Byte")
                HorizontalDivider()
                for (field in decoded.fields) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(field.label, style = MaterialTheme.typography.bodyMedium)
                        Text(field.value, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Mono("Byte ${field.offsets.joinToString(",")} = ${field.raw}")
                        if (!field.verified) Tag("unbestaetigt", warning = true)
                    }
                    if (field.note != null) Note(field.note)
                    HorizontalDivider()
                }
                if (decoded.problems.isNotEmpty()) {
                    Note("Nicht lesbar:", warning = true)
                    for (problem in decoded.problems) Note("- $problem", warning = true)
                }
                Mono("Rohdaten: ${decoded.bodyHex}")
            }
        }

        for (spec in profile.commands.filter { it.kind == CommandKind.WRITE }) {
            CommandCard(spec, state, model, connected)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandCard(
    spec: CommandSpec,
    state: UiState,
    model: Boom2ViewModel,
    connected: Boolean,
) {
    SectionCard(spec.label, spec.note) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono("Befehl ${spec.command}")
            if (!spec.verified) Tag("unbestaetigt", warning = true)
        }
        for (argument in spec.arguments) {
            val current = state.arguments[spec.key]?.get(argument.key)
                ?: argument.default
                ?: argument.min
            Text(
                "${argument.label}: $current",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                argument.choices.isNotEmpty() -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (choice in argument.choices) {
                        FilterChip(
                            selected = current == choice.value,
                            onClick = {
                                model.setArgument(spec.key, argument.key, choice.value)
                            },
                            label = { Text(choice.label) },
                        )
                    }
                }

                argument.min < argument.max -> Slider(
                    value = current.coerceIn(argument.min, argument.max).toFloat(),
                    onValueChange = { raw ->
                        model.setArgument(spec.key, argument.key, raw.roundToInt())
                    },
                    valueRange = argument.min.toFloat()..argument.max.toFloat(),
                )

                else -> Note("Fester Wert ${argument.min}.")
            }
        }
        Button(
            onClick = { model.send(spec) },
            enabled = connected && !state.readOnly,
        ) {
            Text("Senden")
        }
        if (state.readOnly) Note("Nur-Lesen-Modus ist aktiv.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtocolTab(state: UiState, model: Boom2ViewModel) {
    val clipboard = LocalClipboardManager.current
    var commandText by remember { mutableStateOf("01 01") }
    var payloadText by remember { mutableStateOf("") }
    var captureText by remember { mutableStateOf("") }
    val connected = state.link is LinkState.Connected

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                "Experten-Modus",
                "Nur zum Kartografieren des Protokolls. Gesperrte Befehle bleiben auch " +
                    "hier gesperrt.",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in RawMode.entries) {
                        FilterChip(
                            selected = state.rawMode == mode,
                            onClick = { model.setRawMode(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        RawMode.OFF -> "aus"
                                        RawMode.KNOWN_CATEGORIES -> "bekannte Kategorien"
                                        RawMode.UNRESTRICTED -> "unbeschraenkt"
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                "Sondierung",
                "Schickt die Status-Abfrage einmal je Deutung des Laengenfelds und sagt, " +
                    "auf welche die Box antwortet.",
            ) {
                Note(
                    "Das Laengenfeld ist der einzige Teil des Rahmens, den keine Quelle " +
                        "festnagelt. Eine Box, die die Verbindung annimmt und dann " +
                        "schweigt, sieht genau so aus wie eine Box, die den Rahmen nicht " +
                        "versteht. Zwei Pakete klaeren das.",
                )
                Button(
                    onClick = { model.probeFraming() },
                    enabled = connected && !state.probeBusy,
                ) {
                    Text(if (state.probeBusy) "Laeuft ..." else "Rahmen sondieren")
                }
                for (line in state.probeResult) {
                    Note(line, warning = !line.contains("ANTWORT"))
                }
            }
        }

        item {
            SectionCard("Roh-Befehl senden") {
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    label = { Text("Befehl, 2 Byte (z. B. 01 01)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = payloadText,
                    onValueChange = { payloadText = it },
                    label = { Text("Payload in Hex (leer erlaubt)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = { model.sendRaw(commandText, payloadText) },
                    enabled = connected && state.rawMode != RawMode.OFF,
                ) {
                    Text("Senden")
                }
                if (state.rawMode == RawMode.OFF) {
                    Note("Experten-Modus ist aus, es wird nichts gesendet.")
                }
            }
        }

        item {
            SectionCard(
                "Rahmen pruefen",
                "Pruefe Header, Laengenfeld und Pruefsumme gegen echte Bytes, statt sie " +
                    "zu glauben.",
            ) {
                OutlinedTextField(
                    value = captureText,
                    onValueChange = { captureText = it },
                    label = { Text("Hex-Mitschnitt einfuegen") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { model.analyzeCapture(captureText) },
                        enabled = captureText.isNotBlank(),
                    ) {
                        Text("Analysieren")
                    }
                    OutlinedButton(onClick = { model.analyzeAuditLog() }) {
                        Text("Bytes dieser Sitzung")
                    }
                }
                val report = state.capture
                if (report != null) {
                    HorizontalDivider()
                    for (line in report.conclusion) Note(line)
                    for (semantics in LengthSemantics.entries) {
                        val detail = report.report(semantics)
                        Mono(
                            "$semantics: ${detail.framesOk} ok, ${detail.framesBad} " +
                                "fehlerhaft, ${detail.bytesDiscarded} Byte verworfen",
                        )
                    }
                    if (report.commandHistogram.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Befehle im Mitschnitt", style = MaterialTheme.typography.bodyMedium)
                        for ((command, count) in report.commandHistogram) {
                            Mono("$command  x$count")
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                "Mitschnitt der Sitzung",
                "${state.audit.size} Eintraege, nur im Speicher. Nichts davon wird " +
                    "gespeichert oder verschickt.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(model.auditExport()))
                            model.note("Mitschnitt in die Zwischenablage kopiert.")
                        },
                    ) {
                        Text("Kopieren")
                    }
                    OutlinedButton(onClick = { model.clearAudit() }) {
                        Text("Leeren")
                    }
                }
            }
        }

        items(state.audit.asReversed()) { entry ->
            AuditRow(entry)
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(
                    text = entry.kind.name,
                    warning = entry.kind == AuditKind.BLOCKED || entry.kind == AuditKind.ERROR,
                )
                Mono("#${entry.seq}  ${clockText(entry.timestampMs)}")
            }
            Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
            if (entry.rule != null) Mono("Regel: ${entry.rule}")
            if (entry.hex != null) Mono(entry.hex)
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun clockText(milliseconds: Long): String =
    if (milliseconds <= 0L) "" else timeFormat.format(Date(milliseconds))
