# Boom2 Local

Eine Android-App ausschliesslich fuer die **Soundcore Boom 2**, die nur den Bluetooth-
Steuerkanal benutzt und alles andere blockt. Kein Konto, kein Login, keine Cloud, keine
Telemetrie, keine Firmware-Updates — und zwar nicht als Versprechen, sondern erzwungen
durch den Build.

## Was die App garantiert

**1. Sie kann technisch nicht ins Netz.**
Im Manifest fehlt `android.permission.INTERNET`. Unter Android ist das keine Einstellung,
sondern eine Kernel-Tatsache: die INTERNET-Berechtigung wird auf eine Gruppen-ID
abgebildet, und ein Prozess ohne diese Gruppe kann keinen Netzwerk-Socket oeffnen. Kein
Code-Pfad, keine Bibliothek und kein Fehler von mir kann daran etwas aendern.

Der Build bricht ab, wenn sich das je aendert:

| Gradle-Task | Prueft |
|---|---|
| `verifySourceManifests` | kein INTERNET, kein Netzwerkstatus, kein Standort, kein Mikrofon, keine Kontakte in den Quell-Manifesten |
| `verifyDebugMergedManifest` / `verifyReleaseMergedManifest` | dasselbe im **zusammengefuehrten** Manifest, also auch wenn eine Bibliothek die Berechtigung fuer dich hinzufuegt |
| `verifyDependencyAllowlist` | nur AndroidX und Kotlin landen im Runtime-Classpath — kein Firebase, kein Analytics, kein HTTP-Client |
| `verifyNoNetworkImports` | der eigene Code importiert kein `java.net`, `okhttp3`, `retrofit2`, `com.google.firebase`, … |
| `verifyProtocolCoreIsPure` | der Protokoll-Kern bleibt frei von Android-APIs und damit testbar |
| CI-Schritt *Show the permissions...* | liest die Berechtigungen mit `aapt2` aus dem **fertig gepackten APK** und bricht ab, wenn eine Netzwerk-Berechtigung darin steht |

Die ersten drei haengen an `preBuild` bzw. `assemble`, du kannst also kein APK bauen, das
die Pruefungen uebersprungen hat. Die App zeigt dieselbe Pruefung zur Laufzeit im
Reiter **Sicherheit**, gelesen aus der installierten App statt aus dem Quelltext.

**2. Sie braucht keine Standort-Berechtigung.**
Die App startet nie einen Bluetooth-Scan — sie liest nur die Liste der bereits
*gekoppelten* Geraete. Genau das Scannen ist der Grund, warum Android-Apps Standort
verlangen. Die Boom 2 muss fuer Audio ohnehin gekoppelt sein, also reicht die Liste.
Einzige Laufzeit-Berechtigung: `BLUETOOTH_CONNECT`, und das erst ab Android 12.

**3. Sie sendet nur, was im Profil steht (Default-Deny).**
Jeder ausgehende Befehl laeuft durch `CommandPolicy`. Was nicht in der Allowlist des
Geraeteprofils steht, wird nicht gesendet. Damit ist die ganze Oberflaeche, die die
Original-App benutzt — Konto-Registrierung, Nutzungsstatistik, Firmware-Uebertragung —
nicht erreichbar, **einschliesslich der Teile, die noch niemand reverse-engineert hat**.
Dazu:

- **Nur-Lesen-Modus** (Standardeinstellung): es wird gar nichts geschrieben.
- **Blockliste** im Profil: schlaegt die Allowlist und auch den Experten-Modus.
- **Payload-Obergrenze** und Argument-Bereiche, damit ein bekannter Befehl nicht mit
  einer unerwartet langen Nutzlast missbraucht werden kann.
- **Mitschnitt** jedes gesendeten und empfangenen Bytes in Hex, nur im Speicher, nie auf
  der Platte, es sei denn du kopierst ihn selbst.

## Was die App nicht kann

**Die Dienste *in* der Box kann man nicht abschalten.** Die Boom 2 laeuft mit
geschlossener Anker-Firmware. Diese App ersetzt die Soundcore-App auf deinem Handy; sie
veraendert die Box nicht. Konkret heisst das:

- Hast du die Original-Soundcore-App noch installiert, sendet die weiter ihre Daten. Dann
  deinstallieren. (Eine Firewall-/DNS-Blockliste gegen die Anker-Endpunkte waere der
  naechste Schritt; die ist hier bewusst nicht enthalten.)
- Reines Musikhoeren laeuft ueber A2DP im Android-Bluetooth-Stack, nicht ueber diese App.
  Sie steuert nur Einstellungen.

**Das Protokoll ist fuer die Boom 2 noch unbestaetigt.** Anker dokumentiert nichts; jeder
Byte-Offset und jeder Befehlscode stammt aus Mitschnitten, die andere veroeffentlicht
haben, und fast nichts davon wurde an einer Boom 2 verifiziert. Siehe
[PROTOCOL.md](PROTOCOL.md). Die App luegt dich darueber nicht an: jeder unbestaetigte
Wert traegt in der Oberflaeche ein rotes **unbestaetigt**.

Realistische Erwartung beim ersten Start: die Verbindung klappt oder sie klappt nicht,
und in beiden Faellen siehst du im Reiter **Protokoll** die rohen Bytes. Der Weg von
"klappt nicht" zu "klappt" steht unten.

## Fertiges APK herunterladen

Gebaut wird in GitHub Actions, du brauchst also nichts zu installieren:

**[boom2-local-klein.apk herunterladen](https://github.com/Citopyth/gin-prometheus/releases/latest/download/boom2-local-klein.apk)**
— die minifizierte Fassung, auf dem Handy die zuverlaessigste.

Falls sie sich merkwuerdig verhaelt, liegt daneben die unveraenderte Fassung, die nicht
durch R8 gegangen ist:
[boom2-local.apk](https://github.com/Citopyth/gin-prometheus/releases/latest/download/boom2-local.apk).
Beide tragen dieselbe Signatur und ersetzen einander beim Installieren; Pruefsummen
stehen in `boom2-local.sha256`.

> Haengt der Download im Browser, liegt es meist nicht an GitHub: ein VPN mit
> Kill-Switch (NordVPN & Co.) blockiert den Verkehr, sobald der Tunnel kurz abreisst,
> und der Browser bleibt dann im Fortschrittsbalken stehen. VPN kurz aus, Download neu
> starten.

Das ist ein Release-Asset: kein GitHub-Login, kein ZIP, auf dem Handy ein Tipp. Der
Link bleibt ueber alle kuenftigen Builds gleich, weil der Workflow immer denselben Tag
`boom2local-latest` aktualisiert. Danach die Datei oeffnen und die Installation aus
unbekannter Quelle bestaetigen.

Alternativ, falls du eine bestimmte aeltere Fassung brauchst: jeder
[Workflow-Lauf](https://github.com/Citopyth/gin-prometheus/actions/workflows/boom2local.yml)
haengt dasselbe APK unten als Artefakt `boom2-local-apk` an. Dieser Weg verlangt
allerdings einen GitHub-Login im Browser und liefert ein ZIP, selbst bei einem
oeffentlichen Repo.

Das APK ist mit einem Schluessel signiert, der zwischen den CI-Laeufen erhalten bleibt.
Ein neuer Build installiert sich deshalb als Update ueber den alten, ohne dass du die
App vorher entfernen musst.

Die Berechtigungsliste des gebauten APK steht im Build-Log, Schritt *Show the
permissions the APK actually requests* — dort kannst du nachlesen, dass im fertigen
Paket wirklich nur die drei Bluetooth-Berechtigungen stehen. Eine vierte, die Android
selbst ergaenzt, ist `de.privat.boom2local.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`:
die legt AndroidX an, um app-eigene Broadcast-Empfaenger gegen Zugriff von aussen zu
schuetzen. Sie gilt nur innerhalb dieser App und hat nichts mit Netzwerk zu tun.

## Selbst bauen

Voraussetzung: Android Studio (Ladybug oder neuer) oder ein Android SDK mit
`compileSdk 35`. Dann:

```bash
cd soundcore-boom2-local
./gradlew :app:assembleDebug          # baut das APK, inkl. aller Sicherheits-Pruefungen
./gradlew :app:check                  # nur die Pruefungen
./gradlew :app:installDebug           # auf ein angeschlossenes Handy
```

Die Protokoll-Tests laufen **ohne Android SDK**, als eigenes Gradle-Projekt gegen
dieselben Quelldateien:

```bash
cd soundcore-boom2-local/protocol-verify
./gradlew test                        # 84 Tests
```

> Hinweis zur Entstehung: in der Umgebung, in der diese App geschrieben wurde, ist
> `dl.google.com` per Netzwerk-Policy gesperrt. Damit waren Android SDK, AGP und
> AndroidX dort unerreichbar, und nur der Protokoll-Kern liess sich lokal bauen und
> testen. Die Android-Schicht wird deshalb in GitHub Actions kompiliert - dort steht
> das SDK bereit. Der Workflow `.github/workflows/boom2local.yml` ist also nicht nur
> die Bequemlichkeit, aus der das APK faellt, sondern die Stelle, an der diese Schicht
> ueberhaupt zum ersten Mal einen Compiler gesehen hat. Beides ist gruen.

## Zwei Transportwege

Die App kann die Frames ueber beide Bluetooth-Wege schicken, umschaltbar im Reiter
*Geraet*:

- **RFCOMM (klassisch)** — der Weg, den alle veroeffentlichten Quellen beschreiben. Die
  App probiert die Dienste, die deine Box per SDP wirklich anbietet, zuerst die
  herstellerspezifischen, danach erst die geratenen Kandidaten aus dem Profil.
- **BLE (GATT)** — fuer den Fall, dass dieses Modell den anderen Weg geht. Welcher
  GATT-Dienst der Steuerkanal ist, entscheidet `GattSelector` im Protokoll-Kern: gesucht
  wird ein nicht-standardisierter Dienst mit je einer beschreibbaren und einer meldenden
  Eigenschaft. Diese Auswahl ist unit-getestet, nicht nur an einem Geraet ausprobiert.
- **automatisch** (Standard) — erst RFCOMM, dann BLE, und die Fehlermeldung nennt am
  Ende beide Versuche.

Framing, Allowlist und Decoder sind in beiden Faellen dieselben; nur das Rohr ist anders.

**Firmware-Dienste sind im BLE-Pfad hart gesperrt**, vor jeder anderen Pruefung: Nordic
Secure DFU (`0000fe59-…`) und das aeltere DFU-Profil (`00001530-…`). Die Command-Allowlist
blockt ohnehin alles Unbekannte, aber ein versehentlicher Schreibzugriff in einen
Update-Dienst ist der eine Fehler, der nicht reparabel ist — der bekommt eine eigene
Regel eine Ebene tiefer. Die Liste ist nicht vollstaendig und steht im Profil unter
`ble.blockedServiceUuids`.

Auch der BLE-Weg **scannt nicht**: er verbindet direkt zum bereits gekoppelten Geraet.
Die Standort-Berechtigung bleibt damit aussen vor.

## Protokoll kartografieren

So kommst du von "unbestaetigt" zu "bestaetigt". Du brauchst dafuer kein Android Studio —
das Geraeteprofil ist eine JSON-Datei, die du in der App ersetzen kannst.

1. **Rahmen pruefen.** Reiter *Protokoll* → verbinden, *Status lesen*, dann
   *Bytes dieser Sitzung*. Der Validator sagt dir, ob Header, Laengenfeld und Pruefsumme
   so stimmen, wie das Profil sie annimmt, und welche Deutung des Laengenfelds passt.
2. **Mitschnitt der Original-App ziehen**, falls Schritt 1 nichts findet. Das ist die
   verlaessliche Quelle:
   - Entwickleroptionen aktivieren, Bluetooth **aus**.
   - *Bluetooth-HCI-Snoop-Log* einschalten, Bluetooth **an**, mit der Box verbinden
     (keine Musik abspielen — das blaeht das Log auf).
   - Soundcore-App oeffnen, genau eine Sache aendern (z. B. nur BassUp umschalten).
   - Fehlerbericht erzeugen, Snoop-Log wieder aus.
   - Im ZIP liegt das Log unter `FS/data/misc/bluetooth/logs/btsnoop_hci.log` bzw.
     `FS/data/log/bt/btsnoop_hci.log`. Mit Wireshark oeffnen, die RFCOMM-Nutzdaten als
     Hex herauskopieren und im Reiter *Protokoll* einfuegen.
   - **Achtung:** so ein Log enthaelt Geraetenamen, MAC-Adressen, Seriennummer und
     Firmware-Version. Nicht unbedacht weitergeben.
3. **Diffs lesen.** Immer nur eine Einstellung aendern und die Frames vergleichen. Das
   geaenderte Byte ist der Offset, den du suchst.
4. **Profil korrigieren.** Reiter *Sicherheit* → *Mitgeliefertes Profil kopieren*, im
   Texteditor anpassen, zurueck einfuegen, *Uebernehmen*. Die App validiert das Profil
   vor dem Speichern, ein Tippfehler kann sie also nicht lahmlegen. Setze
   `"verified": true` erst, wenn du es an deiner Box wirklich gesehen hast.
5. **Findest du einen Firmware-/OTA-Befehl**, traeg ihn in `blockedCommands` ein. Dann ist
   er auch im Experten-Modus gesperrt.

Der Experten-Modus im Reiter *Protokoll* hat drei Stufen: `aus` (nur Profil-Befehle),
`bekannte Kategorien` (beliebige Codes, aber nur in Kategorien, die das Profil kennt) und
`unbeschraenkt`. Die Blockliste gilt in allen drei.

## Aufbau

```
soundcore-boom2-local/
├─ app/src/main/kotlin/de/privat/boom2local/
│  ├─ protocol/          reines Kotlin, kein Android — Rahmen, Policy, Decoder
│  └─ app/               Android: RFCOMM-Transport, ViewModel, Compose-UI
├─ app/src/main/assets/profiles/boom2.json    das Geraeteprofil als Daten
├─ protocol-verify/      eigenes Gradle-Projekt: kompiliert und testet protocol/
│                        ohne Android SDK, gegen dieselben Dateien (keine Kopie)
└─ PROTOCOL.md           Quellen und Verifikationsstand
```

Die Trennung ist Absicht: alles, was ueber Korrektheit entscheidet — Framing, Pruefsumme,
Resynchronisation, Allowlist, Decoder — liegt in `protocol/` und ist auf einer normalen
JVM testbar. Die Android-Schicht darunter ist absichtlich duenn.

## Lizenz

Privates Werkzeug fuer ein eigenes Geraet. Kein Bezug zu Anker Innovations; "Soundcore"
und "Boom 2" sind Marken ihrer jeweiligen Inhaber.
