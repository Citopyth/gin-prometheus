package de.privat.boom2local.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.privat.boom2local.app.Boom2ViewModel
import de.privat.boom2local.app.UiState
import de.privat.boom2local.protocol.Decibels
import de.privat.boom2local.protocol.EqProbe
import de.privat.boom2local.protocol.SoundHistory
import kotlin.math.roundToInt

/**
 * Sound, built so that nothing on it is a one-way door.
 *
 * The layout follows from what went wrong on 2026-11-05: a free slider over the raw
 * byte range put +12 dB on every band at once, the speaker acknowledged it, and the
 * sound was ruined with no way back from inside the app. So the way back comes first
 * here - it is the top card, before anything that changes anything - the gain is in
 * decibels and moves in whole steps, and every change is recorded so one tap undoes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundTab(state: UiState, model: Boom2ViewModel) {
    val profile = state.profile
    val canSend = state.connected && !state.readOnly

    if (profile == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            SectionCard("Kein Profil") {
                Note("Ohne brauchbares Profil gibt es nichts zu steuern.", warning = true)
            }
        }
        return
    }

    val gainArgument = profile.commands
        .firstOrNull { it.key == "eqAllBands" }
        ?.arguments
        ?.firstOrNull { it.key == "gain" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            "Zurueck",
            "Steht absichtlich oben. Nichts weiter unten ist eine Einbahnstrasse.",
        ) {
            Note(SoundHistory.describe(state.soundHistory))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.resetSound() }, enabled = canSend) {
                    Text("Alles auf 0.0 dB")
                }
                OutlinedButton(onClick = { model.undoSound() }, enabled = canSend) {
                    Text("Rueckgaengig")
                }
            }
            Note(
                "Ehrlich gesagt: zurueck heisst zurueck auf einen flachen Equalizer, " +
                    "nicht auf den Zustand, in dem die Box ab Werk war. Den hat niemand " +
                    "gelesen, bevor diese App das erste Mal geschrieben hat. Wenn dir der " +
                    "Klang jetzt nicht gefaellt, ist die Soundcore-App der verlaessliche " +
                    "Weg - die kennt die Werte des Herstellers.",
            )
            if (!canSend) {
                Note(
                    if (!state.connected) {
                        "Nicht verbunden."
                    } else {
                        "Nur-Lesen-Modus ist aktiv - im Reiter Steuern abschaltbar."
                    },
                    warning = true,
                )
            }
        }

        SectionCard(
            "Voreinstellungen der Box",
            "Zehn Indizes, die die Box akzeptiert. Was jeder klingt, sagt sie nicht.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (index in 0..9) {
                    FilterChip(
                        selected = false,
                        onClick = { model.applyPreset(index) },
                        enabled = canSend,
                        label = { Text("$index") },
                    )
                }
            }
            Note(
                "Die Methode ist das Ohr: eine antippen, hoeren, die naechste. Jede " +
                    "ersetzt die vorherige, und jeder Schritt steht im Protokoll mit " +
                    "Uhrzeit. Sag mir, welche dir gefaellt, dann bekommt sie im Profil " +
                    "einen Namen statt einer Nummer.",
            )
            Note(
                "Index 255 ging am 05.11.2026 einmal raus und wurde quittiert, obwohl es " +
                    "ihn fast sicher nicht gibt. Das Profil laesst jetzt nur 0 bis 9 zu.",
                warning = true,
            )
        }

        if (gainArgument != null) {
            var raw by remember { mutableIntStateOf(gainArgument.default ?: Decibels.NEUTRAL_RAW) }
            SectionCard(
                "Alle Baender zugleich",
                "Ganze Dezibel, ${Decibels.format(Decibels.fromRaw(gainArgument.min))} bis " +
                    "${Decibels.format(Decibels.fromRaw(gainArgument.max))}.",
            ) {
                Text(
                    gainArgument.render(raw),
                    style = MaterialTheme.typography.titleMedium,
                )
                val stepCount = ((gainArgument.max - gainArgument.min) / gainArgument.step) - 1
                Slider(
                    value = raw.toFloat(),
                    onValueChange = { value ->
                        val snapped = gainArgument.min +
                            (((value - gainArgument.min) / gainArgument.step).roundToInt() *
                                gainArgument.step)
                        raw = snapped.coerceIn(gainArgument.min, gainArgument.max)
                    },
                    valueRange = gainArgument.min.toFloat()..gainArgument.max.toFloat(),
                    steps = if (stepCount > 0) stepCount else 0,
                )
                Button(onClick = { model.applyAllBands(raw) }, enabled = canSend) {
                    Text("Senden")
                }
                Note(
                    "Der Regler rastet in ganzen Dezibel ein, und die Allowlist laesst " +
                        "auch nichts anderes durch - nicht nur die Oberflaeche. Genau " +
                        "hier ging am 05.11.2026 der Rohwert f0 raus, also " +
                        "${Decibels.format(Decibels.fromRaw(0xf0))} auf allen neun " +
                        "Baendern. So viel Pegel treibt den Verstaerker dauerhaft in den " +
                        "Limiter, und der nimmt zuerst den Bass - es klingt wie ein " +
                        "ausgefallener Subwoofer.",
                    warning = true,
                )
            }
        }

        SectionCard("BassUp") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.applyBassUp(true) }, enabled = canSend) {
                    Text("An")
                }
                OutlinedButton(onClick = { model.applyBassUp(false) }, enabled = canSend) {
                    Text("Aus")
                }
            }
            val live = state.live["bassUp"]
            if (live != null) {
                KeyValue("Die Box meldet", live.value)
            } else {
                Note(
                    "Die Box meldet BassUp von sich aus, sobald es sich aendert - auch " +
                        "wenn du die Taste am Geraet druckst. Hier steht noch nichts, " +
                        "also kam seit dem Verbinden keine Meldung.",
                )
            }
        }

        val bands = state.deviceState?.field("eqGain")
        SectionCard(
            "Was die Box ueber ihren Equalizer meldet",
            "Nur Anzeige.",
        ) {
            if (bands == null) {
                Note(
                    "Noch kein Zustandsrahmen gelesen. Im Reiter Steuern auf " +
                        "\"Status lesen\" - die Box antwortet darauf nicht immer, das " +
                        "ist ein offener Punkt.",
                )
            } else {
                Mono(bands.value)
                Mono("Bytes ${bands.offsets.joinToString(",")}")
            }
            Note(
                "Der erste je empfangene Zustandsrahmen hat gezeigt: ab Byte 34 steht " +
                    "eine LISTE aus Dreiergruppen (Verstaerkung, Band, 0a), keine neun " +
                    "festen Offsets. Ein aelterer Mitschnitt hatte neun solche Gruppen, " +
                    "der neue genau eine - naemlich die, die diese App zuletzt " +
                    "geschrieben hat. Was das Band-Byte auswaehlt, findet die Suche " +
                    "unten heraus.",
            )
        }

        SectionCard(
            "Band-Maske erforschen",
            "Der letzte fehlende Baustein fuer einen echten 9-Band-Equalizer.",
        ) {
            val step = EqProbe.plan().getOrNull(state.probeStep)
            if (step == null) {
                Note("Alle ${EqProbe.STEPS} Schritte sind durch.")
            } else {
                Text(step.label, style = MaterialTheme.typography.titleMedium)
            }
            Note(
                "Die Verstaerkung ist hier fest auf ${Decibels.format(
                    Decibels.fromRaw(EqProbe.PROBE_RAW),
                )} verdrahtet und kein Regler - an genau dieser Stelle ging einmal " +
                    "${Decibels.format(Decibels.fromRaw(0xf0))} auf alle Baender. Die " +
                    "Maske nimmt nur Einzelbits an, damit ein Schritt genau ein Band " +
                    "bewegt.",
            )
            Note(
                "Drei Knoepfe statt eines Durchlaufs, mit Absicht: das Ganze haengt " +
                    "daran, dass die Box 01:01 beantwortet, und das tut sie nicht immer. " +
                    "Ein Automatiklauf wuerde verstecken, welcher der drei Schritte " +
                    "gescheitert ist.",
            )
            Button(onClick = { model.probeBaseline() }, enabled = canSend) {
                Text("1. Neutral + Zustand lesen")
            }
            KeyValue(
                "Grundlinie",
                if (state.probeBaseline == null) "fehlt" else "steht",
            )
            Button(
                onClick = { model.probeSend() },
                enabled = canSend && state.probeBaseline != null && step != null,
            ) {
                Text("2. Schritt senden")
            }
            Button(
                onClick = { model.probeCompare() },
                enabled = canSend && state.probeBaseline != null,
            ) {
                Text("3. Lesen und vergleichen")
            }
            OutlinedButton(onClick = { model.probeReset() }) {
                Text("Suche verwerfen")
            }
            if (state.probeFindings.isNotEmpty()) {
                HorizontalDivider()
                for (finding in state.probeFindings) Note("- $finding")
            }
        }
    }
}

/**
 * Transport control that works with any Bluetooth speaker.
 *
 * This is the part of "universal Bluetooth remote" that actually can be universal.
 * Play, pause, skip and volume are standardised in AVRCP and every Bluetooth audio
 * device implements them; lights, equaliser and battery are not, which is why the rest
 * of this app needs a profile per model.
 *
 * Nothing here goes to the speaker directly: the keys go to whichever player holds
 * audio focus on the phone, and the speaker follows. That is also why these buttons
 * keep working when the vendor channel will not connect at all.
 */
@Composable
fun RemoteTab(state: UiState, model: Boom2ViewModel) {
    // The volume can be changed from anywhere - the hardware keys, another app - so it
    // is read when this screen appears rather than trusted from whenever it was last set.
    LaunchedEffect(Unit) { model.refreshMediaVolume() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            "Wiedergabe",
            "Funktioniert mit jeder Bluetooth-Box, auch ohne Profil und ohne Verbindung " +
                "ueber den Steuerkanal.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.mediaPrevious() }) { Text("|<<") }
                Button(onClick = { model.mediaPlayPause() }) { Text("Play / Pause") }
                OutlinedButton(onClick = { model.mediaNext() }) { Text(">>|") }
            }
            Note(
                "Das sind die Standard-Medientasten. Sie gehen an den Player, der auf dem " +
                    "Handy gerade den Ton hat - Spotify, YouTube, was auch immer - und die " +
                    "Box folgt, weil genau dafuer AVRCP da ist. Es wird also nichts an die " +
                    "Box selbst geschickt.",
            )
        }

        SectionCard("Lautstaerke") {
            val volume = state.mediaVolume
            val max = state.mediaVolumeMax
            if (volume != null && max != null && max > 0) {
                KeyValue("Medien-Lautstaerke", "$volume von $max")
                // The reason this slider exists: a capture of real use held 65 lines of
                // "lauter" and "leiser" in bursts of fifteen to seventeen, 124 ms apart.
                // One tap is one step, and the scale has that many steps, so running it
                // end to end meant drumming on a button. Dragging does it in one move,
                // and costs one log line instead of seventeen.
                var pending by remember(volume) { mutableIntStateOf(volume) }
                Text(
                    "$pending von $max",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = pending.toFloat(),
                    onValueChange = { value ->
                        pending = value.roundToInt().coerceIn(0, max)
                    },
                    onValueChangeFinished = { model.setMediaVolume(pending) },
                    valueRange = 0f..max.toFloat(),
                    steps = if (max > 1) max - 1 else 0,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.mediaVolumeDown() }) { Text("Leiser") }
                OutlinedButton(onClick = { model.mediaVolumeUp() }) { Text("Lauter") }
            }
            Note("Die Knoepfe bewegen eine Stufe, der Regler springt direkt hin.")
            val live = state.live["volume"]
            if (live != null) {
                HorizontalDivider()
                KeyValue("Die Box meldet", live.value)
                Note(
                    "Die Box schickt ihre eigene Lautstaerke von sich aus. Wenn dieser " +
                        "Wert mitlaeuft, beherrscht die Strecke absolute Lautstaerke - " +
                        "dann regelt dieser Knopf wirklich die Box und nicht nur das Handy.",
                )
            }
            Note(
                "Bei eingeschaltetem \"Nicht stoeren\" kann Android die Lautstaerke " +
                    "sperren. Dafuer braeuchte die App Zugriff auf die " +
                    "Benachrichtigungs-Richtlinie, und den will sie nicht haben.",
            )
        }

        SectionCard(
            "Warum der Rest nicht universal sein kann",
            null,
        ) {
            Note(
                "Licht, Equalizer, Akkustand und Kopplungsliste liegen hinter " +
                    "herstellereigenen Befehlscodes. Die sind nicht standardisiert, nicht " +
                    "veroeffentlicht und von Modell zu Modell verschieden - fuer die Boom 2 " +
                    "stehen sie in einem Profil, das aus Mitschnitten dieser einen Box " +
                    "entstanden ist. Eine Universal-Fernbedienung dafuer kann es nicht " +
                    "geben; dafuer braeuchte es pro Modell genau diese Arbeit.",
            )
        }
    }
}
