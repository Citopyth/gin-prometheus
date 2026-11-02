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

## Bauen

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

> Hinweis zur Entstehung: der Protokoll-Kern wurde in der Umgebung, in der diese App
> geschrieben wurde, tatsaechlich kompiliert und getestet (84 Tests, grün). Die
> Android-Schicht **nicht** — dort war `dl.google.com` per Netzwerk-Policy gesperrt, also
> gab es kein Android SDK und kein AGP. Dein erster `./gradlew :app:assembleDebug` ist
> damit der erste echte Compile dieser Schicht. Wenn dabei etwas klemmt, ist es
> erwartungsgemaess eine Kleinigkeit in der Compose-Oberflaeche, nicht in der Logik.

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
