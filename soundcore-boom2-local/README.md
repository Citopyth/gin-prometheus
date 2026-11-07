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
| `verifyDebugMergedManifest` / `verifyReleaseMergedManifest` | dasselbe im **zusammengefuehrten** Manifest, also auch wenn eine Bibliothek die Berechtigung fuer dich hinzufuegt — und zusaetzlich, dass ausser dem Starter-Bildschirm **keine Komponente exportiert** ist |
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

Das fertige APK verlangt genau vier Berechtigungen — `BLUETOOTH_CONNECT`, `BLUETOOTH`
und `BLUETOOTH_ADMIN` (die beiden letzten nur bis Android 11) sowie eine
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` im eigenen Namensraum, die AndroidX anlegt
und die niemandem etwas erlaubt.

**Und sie hat keine Tuer nach draussen.** Die einzige von aussen erreichbare Komponente
ist der Starter-Bildschirm. `androidx.profileinstaller` kommt hinter Compose
transitiv mitgereist und bringt einen **exportierten** Broadcast-Empfaenger mit, damit
eine Shell oder Play das Einspielen eines Startprofils anstossen kann. Er ist durch
`android.permission.DUMP` geschuetzt, die keine normale App bekommt — also kein Loch,
aber eine Tuer an einer App, deren Anspruch es ist, keine zu haben. Das Manifest
entfernt ihn mit `tools:node="remove"`.

Die Pruefung hat sich sofort bezahlt: sie fand eine **zweite** exportierte Komponente,
die ich von Hand uebersehen hatte — `androidx.compose.ui.tooling.PreviewActivity`, aus
`ui-tooling`. Die war nur im **Debug**-APK, nicht in dem, das du installierst. Da dieses
Projekt kein einziges `@Preview` enthaelt, sind beide Tooling-Abhaengigkeiten jetzt raus;
das loescht die Ursache statt das Symptom.

Von hier an bricht der Build ab, wenn je wieder eine exportierte Komponente auftaucht,
auch aus einer Bibliothek.

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

## Klang: nichts davon ist eine Einbahnstrasse

Der Reiter **Klang** ist so gebaut, wie er gebaut ist, weil etwas schiefging. Am
05.11.2026 ging `02 88` mit der Verstaerkung `f0` an eine echte Box - das sind **+12 dB
auf allen neun EQ-Baendern**, die Box quittierte nach 31 ms, und der Klang war danach
hoerbar ruiniert. So viel Pegel treibt den Verstaerker dauerhaft in den Limiter, und der
nimmt zuerst den Bass. Es klingt wie ein ausgefallener Subwoofer.

Der Grund war ein Regler, der den rohen Bytebereich 0..240 frei durchfahren liess.
Jetzt:

- **Die Rueckwege stehen oben**, vor allem, was etwas aendert: *Alles auf 0.0 dB* und
  *Rueckgaengig*.
- **Jede Aenderung ist umkehrbar.** `SoundHistory` im Protokoll-Kern merkt sich, was die
  App gesetzt hat; *Rueckgaengig* geht einen Schritt zurueck, und der Boden des Stapels
  ist der flache Equalizer.
- **Die Verstaerkung ist in Dezibel** und rastet in ganzen Schritten von -6,0 bis +6,0
  dB ein. Nicht nur in der Oberflaeche: die Allowlist prueft dasselbe Raster, ein
  handgesetzter Wert kommt also auch nicht durch. `f0` ist nicht mehr erreichbar.
- **Die Voreinstellungen der Box** stehen in einem Auswahlmenue, 0 bis 9, und du kannst
  sie **selbst benennen**. Die naheliegende Loesung waere, die Namen mitzuliefern -
  Signature, Bass Booster und so weiter -, aber die Liste gehoert zu *anderen* Modellen.
  Keine Quelle sagt, welcher Index auf einer Boom 2 was bedeutet, und zehn selbstsichere
  Etiketten ueber zehn unbekannten Zahlen waeren dieselbe geerbte Behauptung, die schon
  neun EQ-Baender an die falschen Offsets gesetzt hat - nur wuerdest du diesmal das eine
  hoeren und das andere lesen. Also: antippen, hinhoeren, Namen eintragen. Er bleibt
  gespeichert und steht danach im Menue. Der Index 255, der damals quittiert wurde, ist
  gesperrt.

**Was "zurueck" ehrlich heisst:** zurueck auf einen flachen Equalizer, nicht auf den
Zustand, in dem die Box ab Werk war. Den hat niemand gelesen, bevor diese App das erste
Mal geschrieben hat. Wenn der Klang nicht stimmt, ist die **Soundcore-App** der
verlaessliche Weg - die kennt die Werte des Herstellers.

**Einzelne Baender** kann die App noch nicht einstellen - dafuer fehlt die Bit-Belegung
der Band-Maske. Die laesst sich aber finden, und der Reiter *Klang* hat das Werkzeug
dafuer: Grundlinie setzen, **ein** Maskenbit auf +2,0 dB, Zustand lesen, vergleichen.
Die Verstaerkung ist dort fest verdrahtet und kein Regler, die Maske nimmt nur
Einzelbits an, und drei getrennte Knoepfe statt eines Automatiklaufs zeigen, welcher
Schritt scheitert - denn die Methode haengt daran, dass die Box `01:01` beantwortet, und
das tut sie nicht immer. Die Regeln stehen in `EqProbe` und sind getestet; einmal
geraten zu haben, hat gereicht.

Was das bringt, wenn es klappt: tiefe Baender ein paar Dezibel zurueck, Mitten vor -
dann greift der Schutz-Limiter spaeter und es wird lauter, ohne den Bass zu verlieren.
Mehr Verstaerkerleistung kann auch kein Firmware-Update erfinden.

## Universelle Fernbedienung - der Teil, der wirklich universal ist

Der Reiter **Fernbed.** steuert Wiedergabe und Lautstaerke und funktioniert mit **jeder**
Bluetooth-Box, auch ohne Profil und auch dann, wenn der Steuerkanal gar nicht verbindet.

Der Grund ist der Unterschied zwischen zwei Ebenen: Play, Pause, Titelwechsel und
Lautstaerke sind standardisiert (AVRCP) und jedes Bluetooth-Audiogeraet beherrscht sie.
Licht, Equalizer, Akkustand und Kopplungsliste sind es nicht - die liegen hinter
herstellereigenen Codes, die pro Modell anders sind und nirgends veroeffentlicht. Fuer
die Boom 2 stehen sie in einem Profil, das aus Mitschnitten dieser einen Box entstanden
ist. **Eine Universal-Fernbedienung fuer diese Ebene kann es nicht geben.**

Praezise gesagt: die Medientasten gehen an den Player, der auf dem Handy den Ton hat,
und die Box folgt. An die Box selbst wird dabei nichts geschickt. Genau deshalb braucht
dieser Reiter keine einzige zusaetzliche Berechtigung.

**Die Lautstaerke hat einen Schieberegler**, und zwar wegen eines Mitschnitts aus echter
Benutzung: 65 Zeilen "lauter" und "leiser" in Schueben von 15 bis 17, im Abstand von
124 Millisekunden. Ein Druck ist eine Stufe, und so viele Stufen hat die Skala - die
Skala einmal durchzufahren hiess also, auf einem Knopf zu trommeln. Der Regler macht es
in einer Bewegung und kostet eine Protokollzeile statt siebzehn. Die Plus/Minus-Knoepfe
bleiben fuer die Feinarbeit.

Wenn Android den direkten Sprung ablehnt - feste Lautstaerke, oder "Nicht stoeren" ohne
Richtlinien-Zugriff, den die App nicht hat - laeuft der Regler mit genau den
Einzelschritten dorthin, die die Knoepfe benutzen. Die funktionieren nachweislich.

## Nur ein Geraet soll die Box benutzen duerfen

Das geht - aber nicht aus dieser App heraus, und das ist keine Bequemlichkeit, sondern
eine Tatsache: wen die Box annimmt, entscheidet ihre Firmware anhand ihrer eigenen
Kopplungsliste, und **es gibt keinen bekannten Befehl, der daraus einen Eintrag
loescht**. Keine der Quellen Q1-Q6 beschreibt einen. Einen zu raten ist der Weg, mit dem
man sein eigenes Handy aussperrt.

Was es verlaesslich erreicht:

1. **Box auf Werkseinstellungen zuruecksetzen.** Danach ist ihre Kopplungsliste leer.
   Die Tastenkombination steht im Handbuch - hier absichtlich nicht geraten.
2. **Nur das eigene Handy koppeln.**
3. **Danach die Kopplungstaste an der Box nicht mehr druecken.** Das ist der einzige Weg,
   auf dem waehrend des Betriebs ein neues Geraet hineinkommt.
4. **PartyCast / Sound Share** startet ebenfalls nur ueber eine Taste an der Box.
5. Falls die Box in der Soundcore-App eine Einstellung fuer zwei gleichzeitige Geraete
   hat, dort abschalten.

Nachsehen laesst es sich vielleicht von hier aus: der Reiter **Sicherheit** hat einen
Knopf, der die Box nach ihrer Kopplungsliste fragt (`0b:01`/`0b:02`, bei Q4 fuer die
Space 2 beschrieben). Die Abfrage liest nur, sie aendert nichts.

Dieser Knopf sah lange tot aus, und das war ein Fehler: er schickte die Abfrage, und die
Karte aenderte sich nie. Eine Antwort landete nur im Protokoll als undeutbarer Rahmen,
Schweigen ebenfalls - von aussen nicht zu unterscheiden, und beides nicht von einem
kaputten Knopf. Jetzt zeigt die Karte alle vier Zustaende: noch nicht gefragt, wartet,
hat geantwortet (mit den Rohbytes, ausdruecklich ungedeutet), oder fuenf Sekunden lang
nichts gesagt. Diese Box hat bisher in **keinem** Mitschnitt geantwortet - das ist dann
eine Aussage ueber die Box, nicht ueber die App.

Umgekehrt ist es dicht: die App redet nur mit der Box, an die sie gebunden ist - eine
MAC-Adresse, jedes andere Geraet wird abgelehnt. Das schuetzt die Box vor dieser App,
nicht vor fremden Handys.

### Zwei Dinge, die die App trotzdem tut

**Sie nimmt jetzt den starken Socket.** Die Mitschnitte zeigen, dass die Box den
Steuerkanal **ohne Authentifizierung** annimmt - und dass sie *mit* auch funktioniert.
Sie verlangt also nichts und nimmt, was man ihr anbietet, und diese App hat ihr per
Voreinstellung die schwache Variante angeboten, weil die zuerst funktionierte. Das war
ein Fehler. `SocketPlan` fragt jetzt zuerst nach Authentifizierung und faellt nur
zurueck, wenn sie abgelehnt wird. Was das bringt: wer die Adresse deiner Box faelscht,
aber den Kopplungsschluessel nicht hat, kommt durch den authentifizierten Handshake
nicht. Was es **nicht** bringt: es haelt kein anderes Geraet von deiner Box ab. Welche
Variante zustande kam, steht in der Verbindungskarte und im Protokoll - die schwache wird
rot markiert.

**Sie merkt, wenn jemand anderes etwas verstellt.** Die Box meldet Lautstaerke, BassUp
und Lichtzustand von sich aus, an echter Hardware belegt. Zieht man ab, was die App
selbst geschickt hat, bleibt ein Protokoll mit Uhrzeit von allem anderen, was die Box
angefasst hat. Das steht im Reiter **Sicherheit** unter *Wachmodus*.

Eine fremde Aenderung ist dabei **kein Angriff**: die Tasten an der Box landen genauso
dort, die Lautstaerke-Wippe des Handys auch, und die Box selbst, wenn sie sich
herunterregelt. Die Liste sagt nur: das kam nicht von dieser App. Fuer drei fallende
Lautstaerke-Meldungen hintereinander sagt sie es ausdruecklich - das ist die Box, die
ihren Treiber schuetzt, und damit auch die Antwort auf "warum wird es von selbst leiser".

**Verhindern kann sie nichts davon.** Das ist keine Bequemlichkeit, sondern die
Architektur: der Steuerkanal ist ein Dienst der *Box*.

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

## Lokal pruefen, was lokal pruefbar ist

Die Android- und Compose-Jars sind von der Entwicklungsmaschine dieses Projekts aus
nicht erreichbar, also laesst sich die App-Schicht hier **nicht** kompilieren — jeder
Verweis darauf gilt als unaufgeloest, hunderte Male. Der Protokoll-Kern dagegen baut und
testet lokal vollstaendig:

```
cd protocol-verify && ./gradlew test
```

Fuer die App-Schicht gibt es `tools/kcheck.sh`. Es filtert die Android-Fehler **nicht**
nach Stichwoertern — genau das hat einmal einen fehlenden `import CommandKind` bis in den
CI durchgelassen, weil der Name ein alter war und durch ein Raster fiel, das nach neuen
suchte. Stattdessen vergleicht es die ganze Fehlermenge mit der eines Commits, den der CI
als gruen bewiesen hat:

```
tools/kcheck.sh baseline <gruener-commit>   # Grundlinie aufnehmen
tools/kcheck.sh check                       # was der Arbeitsstand hinzugefuegt hat
```

Nennt eine Zeile etwas Eigenes aus diesem Projekt, ist es ein echter Fehler. Nennt sie
ein Android- oder Compose-Symbol, das die Grundlinie noch nie gesehen hat, ist es
Rauschen — kurz pruefen, ob das Symbol existiert und importiert ist. Der CI bleibt die
einzige Stelle, an der dieser Code wirklich uebersetzt wird.

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

## Mikrofon, Freisprechen, fremde Geraete

Drei Wuensche, die oft zusammen kommen, mit drei verschiedenen Antworten.

**"Die Box soll nur mein Handy akzeptieren."** Geht von aussen nicht. Welche Geraete
eine Box annimmt, entscheidet ihre eigene Firmware, und die ist geschlossen. Kein
Bluetooth-Paket aendert daran etwas, und in keiner Quelle ist ein Befehl beschrieben,
der einen Eintrag aus der Kopplungsliste entfernt. Der Weg, der wirklich funktioniert:
**Box auf Werkseinstellungen zuruecksetzen, dann ausschliesslich dein Handy koppeln.**
Danach ist ihre Liste leer bis auf dich.

Was die App beitragen kann: die Lesebefehle `0b:01` und `0b:02` sollen beim Space 2 die
Kopplungsliste zeigen (Quelle Q4, fuer die Boom 2 unbestaetigt). Sie liegen im Profil,
rein lesend - du siehst damit, wer sonst noch drinsteht, loeschen kannst du es nicht.

**"Hands-Free AG, Mikrofon und die unbekannten Dienste sollen weg."** Loeschen kann sie
niemand von aussen: es sind Eintraege im SDP-Verzeichnis der Box, also wieder Firmware.
Abschalten laesst sich, was *dein Handy* davon benutzt - aber auch das nicht aus dieser
App heraus, denn `setConnectionPolicy()` verlangt `BLUETOOTH_PRIVILEGED`, das nur
System-Apps bekommen. Es geht in den Android-Einstellungen, und dort systemweit:

1. Einstellungen → Verbundene Geraete → Zahnrad neben der Boom 2
2. **"Anrufe" ausschalten** — das ist der Freisprech- und Mikrofon-Kanal (HFP/HSP)
3. **"Kontaktfreigabe" ausschalten**, falls vorhanden (PBAP, Telefonbuch)
4. **"Medienaudio" anlassen** — sonst spielt die Box keine Musik mehr

Danach taucht die Box nicht mehr als Freisprecheinrichtung auf. Dieselbe Anleitung steht
im Reiter *Sicherheit* der App.

Wobei die App hilft: sie **benennt** die Dienste, statt eine Spalte Hex-Zahlen zu zeigen.
`0000111f-…` heisst "Hands-Free Audio Gateway", und daneben steht `Mikrofon`. Dienste,
die weiter reichen als Audio, werden eigens aufgezaehlt — Mikrofon, Daten vom Handy,
Netzwerk, und alles, was der Katalog nicht kennt, als `unbekannt` statt als Vermutung.

**"Die App soll nur mit meiner Box reden."** Das geht, und zwar exakt: *Gerät* →
**An dieses Geraet binden**. Danach spricht die App ausschliesslich mit dieser
Bluetooth-Adresse und lehnt jedes andere Geraet ab, auch eine zweite Boom 2 mit
demselben Namen. Namen taugen dafuer nicht — sie sind frei waehlbar und bei zwei
Geraeten desselben Modells identisch.

## Mehrere Geraete, mehrere Profile

Ein Geraeteprofil ist eine JSON-Datei, kein Code. Die App traegt deshalb eine Bibliothek
davon und sucht beim Verbinden selbst das passende heraus.

Ausgeliefert werden zwei:

| Profil | Greift bei | Schreibbefehle |
|---|---|---|
| **Soundcore Boom 2** | Geraeten, die `0cf12d31-…-d6832e7b3138` anbieten | 6 |
| **Soundcore (unbekanntes Modell)** | jedem Geraet mit dem Hersteller-Praefix `0cf12d31-…-d6832e` | **keine** |

**Erkannt wird am Dienst, nicht am Namen.** Ein Name laesst sich auf alles umstellen und
ist bei zwei Geraeten desselben Modells gleich; die Dienst-UUID steht in der Firmware
und traegt bei Soundcore sogar die Modellnummer. Die Rangfolge:

1. Das Geraet bietet genau den Dienst an, den ein Profil nennt → es ist dieses Modell
2. Es bietet einen Dienst mit dem Hersteller-Praefix an → richtiger Hersteller, Modell offen
3. Nur der Name passt → schwaechster Treffer

Ein umbenannter Lautsprecher wird deshalb weiter erkannt, und ein fremdes Geraet mit
zufaellig passendem Namen nicht.

**Das generische Profil enthaelt absichtlich keinen einzigen Schreibbefehl.** Weil die
App nur sendet, was im Profil steht, kann sie damit auf einem unbekannten Modell nichts
veraendern — lesen und zuhoeren, mehr nicht. Auf einem Geraet, dessen Protokoll niemand
kartografiert hat, waere jeder Schreibzugriff ein Schuss ins Dunkle.

Im Reiter *Sicherheit* kannst du die Bibliothek sehen, ein Profil von Hand erzwingen,
jedes kopieren, eigene hinzufuegen und eigene wieder loeschen. Ein eigenes Profil mit
derselben Kennung ueberschreibt das mitgelieferte und ueberlebt damit ein App-Update.

**Ein weiteres Geraet aufnehmen:** ein mitgeliefertes Profil kopieren, `id`,
`displayName`, `match.serviceUuids` und die Byte-Offsets anpassen, einfuegen. Was dabei
zu tun ist, steht unter *Protokoll kartografieren*; der Weg ueber die Rohbytes im Reiter
*Protokoll* ist derselbe wie bei der Boom 2.

## Zwei Transportwege

Die App kann die Frames ueber beide Bluetooth-Wege schicken, umschaltbar im Reiter
*Geraet*:

- **RFCOMM (klassisch)** — der Weg, den alle veroeffentlichten Quellen beschreiben. Die
  App probiert die Dienste, die deine Box per SDP wirklich anbietet, zuerst die
  herstellerspezifischen, danach erst die geratenen Kandidaten aus dem Profil. Welcher
  zuerst kommt, entscheidet `ServiceCandidates` im Protokoll-Kern — siehe unten.
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

## Nicht jeder Dienst, der die Verbindung annimmt, ist der richtige

Die Boom 2 bietet **mehrere** herstellerspezifische Dienste an, die sich in einem
Hex-Zeichen unterscheiden: `…d6832e7b3138` und `…d6832e7c3138`. Beide passen auf das
Hersteller-Praefix, beide nehmen eine RFCOMM-Verbindung an — aber nur der erste
antwortet. Der zweite schweigt. In welcher Reihenfolge Android die beiden herausgibt,
liegt nicht fest, und in einem Mitschnitt hat die App genau das Falsche gewaehlt: elf
Pakete raus, nichts zurueck, drei Minuten lang als gesunde Verbindung angezeigt.

Dagegen zwei Regeln, beide im Protokoll-Kern und beide unit-getestet:

**Die Reihenfolge der Kandidaten** (`ServiceCandidates`) stellt Wissen vor Vermutung:

1. der Dienst, ueber den *diese* Box schon einmal geantwortet hat. Die App merkt sich
   das — eine Zeile pro Box in ihrem privaten Speicher, hoechstens acht Boxen, loeschbar
   mit dem Pin;
2. der Dienst, den das Profil als Steuerkanal dieses Modells nennt;
3. erst dann ein reiner Praefix-Treffer;
4. danach alles andere Herstellerspezifische, die Rueckfall-Kandidaten aus dem Profil,
   SPP, und auf Wunsch die Standardprofile.

**Eine Verbindung muss sich als lebendig erweisen** (`LinkLiveness`). Drei Dinge muessen
zusammenkommen, bevor ein Dienst abgeschrieben wird:

- die App hat etwas gefragt — sonst sagt Schweigen nichts;
- es sind sechs Sekunden vergangen;
- die App hat dreimal nachgesehen und nichts gefunden.

Dann trennt sie, schliesst den Dienst **fuer diese Sitzung** aus und probiert den
naechsten. Nicht fuer immer — ein Dienst kann auch schweigen, weil die Box gerade
beschaeftigt ist; ein Druck auf *Verbinden* fuellt die Liste wieder auf.

Der dritte Punkt ist nicht dasselbe wie der zweite, und er ist der wichtige. Android
friert eine schlafende App ein und schiebt ihr beim Aufwachen den Stau nach — in zwei
Mitschnitten kamen 396 und 352 Byte gepufferter Meldungen in einem Zug. Die Uhr lief
dabei weiter, der Zaehler nicht. Ohne ihn wuerde die App nach einem Nickerchen genau den
Dienst abschreiben, der funktioniert, und du haettest gar keine Verbindung mehr — weil
der Bildschirm aus war.

## Was bei einem Abbruch passiert

Die Box legt auf. Das kommt vor: ausser Reichweite, ausgeschaltet, oder sie mag nicht
mehr. Was die App daraufhin tut, entscheidet `SessionMachine` im Protokoll-Kern — eine
reine Funktion aus Zustand und Ereignis, und damit testbar, ohne eine Attrappe eines
Bluetooth-Sockets zu bauen.

Die Regel, auf die es ankommt: **eine Abbruchmeldung nennt ihren Transport.** Scheitert
RFCOMM und BLE kommt durch, meldet der aufgegebene RFCOMM-Socket hinterher trotzdem noch
sein Ende. Diese Meldung gehoert zu einer Leitung, die niemand mehr benutzt, und darf die
laufende nicht in einen Fehlerzustand reissen. Dasselbe gilt fuer eine Meldung, die
eintrifft, nachdem du schon auf *Trennen* gedrueckt hast.

Diese Regel ist nicht aus Vorsicht entstanden, sondern weil der Test sie erzwungen hat:
der erste Entwurf konnte die beiden Faelle nicht unterscheiden.

**Automatisch wiederverbinden** ist abschaltbar und standardmaessig **aus**. Eingeschaltet
versucht die App es bis zu dreimal mit wachsendem Abstand (2, 4, 8 Sekunden), und ein
erfolgreicher Neuaufbau setzt das Kontingent zurueck. *Trennen* bricht einen geplanten
Versuch sofort ab. Aus ist die Voreinstellung, weil eine Box, die auflegt, meist einen
Grund hat — und eine App, die endlos neu verbindet, Geraet und Akku beschaeftigt haelt,
ohne dass es jemand merkt.

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
