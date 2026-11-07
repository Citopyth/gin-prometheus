# Soundcore-Steuerprotokoll: was belegt ist und was nicht

Anker veroeffentlicht nichts dazu. Alles hier stammt aus fremden Mitschnitten. Diese
Datei trennt deshalb strikt: **belegt** (eine Quelle zeigt es), **gemeldet** (jemand
behauptet es fuer sein Geraet, unbestaetigt von Dritten), **Hypothese** (meine eigene
Schlussfolgerung beim Bauen dieser App) und **unbekannt**.

Stand der Recherche: 2. November 2026.

## Quellen

| # | Quelle | Was sie beitraegt |
|---|---|---|
| Q1 | [Oppzippy/OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30) | Referenz-Implementierung, 41 unterstuetzte Geraete. **Boom 2 ist nicht dabei.** |
| Q2 | [OpenSCQ30 Issue #360 "Soundcore Boom V2"](https://github.com/Oppzippy/OpenSCQ30/issues/360) | Der einzige echte Boom-2-Mitschnitt, den ich gefunden habe. |
| Q3 | [OpenSCQ30 Issue #315 "Boom 3i"](https://github.com/Oppzippy/OpenSCQ30/issues/315) | Byte-Offsets und Befehlscodes fuer die Boom 3i. |
| Q4 | [mervin008/soundcorebridge, `docs/protocol-map.md`](https://github.com/mervin008/soundcorebridge/blob/main/docs/protocol-map.md) | Vendor-RFCOMM-UUID, Kategorie-Schema, Handshake — alles fuer die **Space 2**. |
| Q5 | [leandrorosa/soundcore-q30](https://github.com/leandrorosa/soundcore-q30) | Bestaetigt Transport und grobe Rahmenstruktur fuer die Life Q30. |
| Q6 | [OpenSCQ30 `docs/capture-bluetooth-logs.md`](https://github.com/Oppzippy/OpenSCQ30/blob/master/docs/capture-bluetooth-logs.md) | Anleitung fuer HCI-Mitschnitte unter Android. |

## Transport

**Belegt (Q4, Q5):** klassisches Bluetooth, RFCOMM. Von BLE/GATT ist in keiner Quelle
die Rede. Alle Quellen betreffen allerdings Kopfhoerer und die Space 2 — fuer einen
Party-Lautsprecher ist GATT damit nicht ausgeschlossen, nur unbelegt. Seit dem ersten
fehlgeschlagenen Verbindungsversuch an echter Hardware (siehe unten) kann die App
**beide** Wege: RFCOMM und GATT, mit identischem Framing. Der urspruengliche Satz hier
lautete, die App benutze ausschliesslich RFCOMM — was zugleich die
Angriffsfläche klein haelt und den Scan (und damit die Standort-Berechtigung) erspart.

**Belegt (Q4):** die UUID ist **nicht** die Standard-SPP-UUID, sondern herstellerspezifisch.
Fuer die Space 2 (Modell D1402): `0cf12d31-fac3-4553-bd80-d6832e7d1402`, RFCOMM-Kanal 30.

**Gemeldet (Q3):** die Boom 3i benutze eine "D5100-RFCOMM-UUID". Die Zeichenkette selbst
steht dort nicht.

**Hypothese (meine):** die letzten vier Hex-Stellen der UUID sind die Modellnummer —
`…d6832e7d` + `1402` fuer D1402, also `…d6832e7d5100` fuer D5100. Fuer die Boom 2 waere
das `0cf12d31-fac3-4553-bd80-d6832e7d3138`, wenn die Boom 2 wirklich A3138 ist (das ist
eine unbestaetigte Nutzerangabe in Q3).

**Bestaetigt am Geraet (2026-11-03):** die Boom 2 bietet per SDP den Dienst
`0cf12d31-fac3-4553-bd80-d6832e7b3138` an und nimmt eine RFCOMM-Verbindung darauf im
ersten Versuch an. Das ist die erste harte Protokoll-Tatsache zur Boom 2 aus dieser
Arbeit. Nebenbei bestaetigt sie die Modellnummer **A3138**, die in Q3 nur eine
unbelegte Nutzerangabe war - die letzten vier Stellen der UUID sind die Modellnummer,
wie beim Space 2 (D1402 -> `…1402`).

Das Byte davor unterscheidet sich aber: Space 2 `…2e7d1402`, Boom 2 `…2e7b3138`. Es ist
also **nicht** der Modellbuchstabe ('D' = 0x44, 'A' = 0x41). Das gemeinsame Praefix
reicht damit nur bis `0cf12d31-fac3-4553-bd80-d6832e`; was `7d` und `7b` bedeuten, ist
offen. Mein urspruengliches Praefix war ein Zeichen zu lang und hat die Box deshalb
nicht erkannt.

**Was weiterhin nicht geht:** auf `01 01` kommt keine Antwort. Die Box nimmt die
Verbindung an, schweigt und beendet den Datenstrom nach rund 27 Sekunden. Beim Space 2
ist genau das das dokumentierte Verhalten **vor** dem Handshake (Q4), was ihn zum
naechsten Verdacht macht; die zweite offene Moeglichkeit ist die Deutung des
Laengenfelds. Beides laesst sich in der App ausprobieren.

**Widerlegt am Geraet (2026-11-03):** ein erster Verbindungsversuch mit einer echten
Boom 2 schlug ueber alle drei Rueckfall-Kandidaten fehl, mit
`read failed, socket might closed or timeout, read ret: -1` — dem Android-Fehler fuer
"diesen Dienst gibt es auf der Gegenstelle nicht". Die zwischengespeicherte Dienstliste
der Box enthielt ausserdem nichts mit dem Praefix `0cf12d31-…`. Die Hypothese oben ist
damit fuer die Boom 2 **nicht** bestaetigt, und `…d6832e7d3138` ist als Kandidat
gescheitert. Offen bleibt, ob die Box eine ganz andere UUID benutzt oder ob Android
beim Koppeln nur die Audio-Profile mitgeschrieben hat; eine frische SDP-Abfrage
(`fetchUuidsWithSdp`) unterscheidet beides, und die App kann das seit diesem Stand.

**Warum das nicht wichtig ist:** die App raet die UUID nicht. Sie liest den SDP-Eintrag
der Box (`BluetoothDevice.uuids`) und nimmt den Dienst, der mit dem Praefix
`0cf12d31-fac3-4553-bd80-d6832e7d` anfaengt. Nur wenn die Box *nichts* Passendes
anbietet, probiert sie die Kandidaten aus dem Profil. Die Hypothese ist also der
Rueckfall, nicht die Grundlage.

### Die Box bietet mehr als einen herstellerspezifischen Dienst an

**Am Geraet bestaetigt (2026-11-06):** der SDP-Eintrag der Boom 2 enthaelt nicht einen,
sondern mindestens drei nicht-registrierte Dienste. Ein Mitschnitt zeigte die App beim
Durchprobieren von allen:

| Dienst | Verbindung | Antwortet |
|---|---|---|
| `0cf12d31-fac3-4553-bd80-d6832e7b3138` | ja | **ja** — Zustandsrahmen, Bestaetigungen, Meldungen |
| `0cf12d31-fac3-4553-bd80-d6832e7c3138` | ja | **nein** — kein einziges Byte |
| `df21fe2c-2515-4fdb-8886-f12c4d67927c` | nein (`read failed`) | — |

Das ist die unangenehmste Entdeckung dieses Mitschnitts. `…7b3138` und `…7c3138`
unterscheiden sich in einem Hex-Zeichen, passen beide auf das Hersteller-Praefix, und in
welcher Reihenfolge Android sie herausgibt, liegt nicht fest. Im Mitschnitt verband sich
die App nach einem Abbruch auf `…7c3138`, schickte darueber einen Zustandsrahmen, einen
siebenschrittigen Handshake und zwei Schreibbefehle — elf Pakete — und bekam nichts
zurueck, waehrend sie die Verbindung drei Minuten lang als gesund meldete. Von aussen
ist das nicht von einer Box zu unterscheiden, die aufgehoert hat zu antworten.

Wozu `…7c3138` da ist, ist offen. Naheliegend waere ein zweiter Kanal fuer die
Soundcore-App (Benachrichtigungen, OTA-Anstoss) oder ein Ueberrest aus der Firmware
einer Schwesterbox. Belegt ist nur, dass er die Verbindung annimmt und schweigt.

**Was die App daraus macht,** in dieser Reihenfolge:

1. der Dienst, ueber den *diese* Box schon einmal geantwortet hat (gemerkt, siehe
   `ProvenServices`);
2. der Dienst, den das Profil unter `match.serviceUuids` als Steuerkanal nennt;
3. erst dann ein reiner Praefix-Treffer.

Und: eine Verbindung, die zustande kommt und nach einer Gnadenfrist auf eine gestellte
Frage nicht geantwortet hat, gilt als toter Dienst (`LinkLiveness`). Sie wird getrennt,
der Dienst fuer diese Sitzung ausgeschlossen und der naechste probiert. Ein Praefix ist
damit das schwaechste der drei Signale, nicht das erste.

## Rahmen

```
 Offset  Groesse  Inhalt
 0       5        Header     08 ee 00 00 00  zur Box
                             09 ff 00 00 01  von der Box
 5       2        Befehl     [Kategorie, Code]
 7       2        Laenge     u16 little-endian
 9       n        Nutzlast
 9+n     1        Pruefsumme Summe aller vorherigen Bytes, auf 8 Bit maskiert
```

**Belegt (Q5):** 2-Byte-Befehl, Little-Endian-Laenge, Ein-Byte-Pruefsumme.

**Gemeldet (aus einer Kotlin-Portierung von OpenSCQ30):** die konkreten Header-Bytes
`08 ee 00 00 00` / `09 ff 00 00 01` und die Pruefsumme als 8-Bit-Summe.

**Am Geraet bestaetigt (2026-11-05):** das Laengenfeld zaehlt den **ganzen Rahmen**.
Drei Status-Antworten der Boom 2 waren je 156 Byte lang, das Laengenfeld las `9c 00` =
156, und die Pruefsumme stimmte in allen drei Faellen. Die Sondierung in der App hat es
unabhaengig bestaetigt: auf die Deutung `TOTAL` kam nach 42 ms eine Antwort, auf `BODY`
gar keine.

Damit sind Header, Laengenfeld und Pruefsummen-Verfahren **vollstaendig bestaetigt** —
der gesamte Rahmen, nicht nur Teile davon.

## Zustand lesen

**Belegt (Q2, Q3, Q4):** Befehl `01 01` fragt bei Space 2, Boom 3i **und Boom 2** den
Zustand ab. Das ist die einzige Protokoll-Aussage, die quer ueber alle drei Geraete
zusammenpasst.

**Am Geraet bestaetigt (2026-11-05).** Ein vollstaendiger Mitschnitt von 146 Nutzbytes,
drei Abfragen ueber 133 Minuten, hat die Tabelle unten entschieden. Die Methode: die
drei Antworten byteweise vergleichen, die sich aendernden Stellen heraussuchen und ihre
Wertemengen mit denen der unaufgeforderten Meldungen abgleichen.

| Feld | Offset | Status | Wie bestaetigt |
|---|---|---|---|
| Lautstaerke | `body[0]` | **bestaetigt** | gleiche Werte wie Meldung `01:09`, die in Buendeln eintrifft |
| Akku | `body[1]` | **bestaetigt** | fiel 2 → 1, im Gleichklang mit Meldung `01:03` |
| BassUp | `body[3]` | **bestaetigt** | wechselte 0 ↔ 1 mit Meldung `02:04`. Frueher stand hier Offset 29 — falsch |
| Firmware | `body[7..12)` | **bestaetigt** | `30 33 2e 31 31` = "03.11" |
| Seriennummer | `body[12..28)` | **bestaetigt**, korrigiert | 16 Zeichen, nicht 17; die 17. Stelle ist ein Steuerzeichen |
| EQ-Baender | `body[34,37,…,58]` | **bestaetigt**, korrigiert | neun Dreiergruppen, alle mit `0x78` = 120 = 0 dB. Abstand 3 stimmte, Start lag zwei zu spaet, und es sind neun statt acht |
| EQ-Voreinstellung | `body[33]` | Vermutung | der alte Offset 35 liegt im EQ-Block und ist widerlegt |
| Licht | `body[67..69]` | unbestaetigt | im Mitschnitt 0 / 0 / 1 |

Die Akku-Obergrenze 5 bleibt unbestaetigt: beobachtet wurden nur 2 und 1. Dass `0x78`
genau 0 dB entspricht, passt zur Formel `120 + dB*10` aus Q4, ist aber nur durch einen
flachen EQ belegt — ein verstelltes Band wuerde es beweisen.

## Bestaetigungen

**Am Geraet bestaetigt (2026-11-06):** die Boom 2 quittiert jeden Schreibbefehl. Die
Antwort ist ein leerer Rahmen mit derselben Befehlsadresse, nach 28 bis 205 ms:

```
→ 08 ee 00 00 00 02 84 0b 00 00 87      02:84 mit Nutzlast 00
← 09 ff 00 00 01 02 84 0a 00 99         02:84, kein Rumpf   = verstanden
```

Quittiert wurden `02:84`, `02:88` (sowohl mit einem als auch mit sechs Nutzbytes),
`0e:08` und `0e:05`. Alle Rahmen stimmten in Laenge und Pruefsumme.

**Was das heisst und was nicht.** Die Box hat fuer diese Adressen und Nutzlastformen
einen Behandler — sie kennt die Befehle. Was der Behandler *tut*, sagt die Bestaetigung
nicht. Dass `02:84` BassUp schaltet, stammt weiterhin aus dem Boom-3i-Mitschnitt. Das
Profil trennt beides: `acknowledged` fuer "quittiert", `verified` fuer "Wirkung gesehen".

**Nicht quittiert:** `0e:07` mit Wert `ff`. Es loeste die Meldung `0e:09 = 01` aus, blieb
aber ohne Bestaetigung. Verdacht: `ff` ist kein gueltiger Lichtmodus. Der gueltige
Wertebereich ist unbekannt.

## Der erste Rahmen nach dem Verbinden geht oft verloren

**Beobachtet ueber vier Sitzungen (2026-11-05/06):**

| Sitzung | Verbindung → erster Rahmen | Antwort |
|---|---|---|
| 1 | 0 ms | nach 88 ms |
| 2 | 1 ms | nach 53 ms |
| 3 | 2 ms | **keine** |
| 4 | 1 ms | **keine** |

Gleicher Code, gleicher Abstand, zweimal Antwort und zweimal nicht. Dass der Kanal in
Ordnung war, zeigt Sitzung 3: 26 Sekunden spaeter wurde jeder Schreibbefehl quittiert.
Der Socket ist also offen, bevor der Steuerdienst der Box zuhoert.

Zwei Gegenmassnahmen, beide im Profil bzw. getestet:

- `transport.settleAfterConnectMs` — eine halbe Sekunde Pause nach dem Socket-Aufbau,
  bevor irgendetwas gesendet wird.
- Unbeantwortete **Lesebefehle** werden bis zu zweimal wiederholt. Schreibbefehle nie:
  eine Frage zweimal zu stellen aendert nichts, einen Schreibzugriff zu wiederholen
  dagegen moeglicherweise schon.

## Die Box kann verstummen, ohne aufzulegen

**Beobachtet (2026-11-06):** nach dem nicht quittierten `0e:07 ff` kamen ueber 36
Sekunden auf zwoelf gesendete Rahmen null Antworten. Die Rahmen-Sondierung blieb
ebenfalls stumm. Der Socket blieb dabei offen, die Leseschleife lief weiter, und die App
meldete jeden Rahmen als gesendet.

Von aussen ist das von einer funktionierenden Verbindung nicht zu unterscheiden. Die App
merkt es jetzt daran, dass Befehle unbeantwortet bleiben: jeder gesendete Befehl wird
vermerkt, jede Antwort loescht ihn wieder, und was laenger als drei Sekunden offen
bleibt, wird gemeldet.

## Meldungen, die die Box von sich aus schickt

**Am Geraet bestaetigt (2026-11-05):** die Boom 2 wartet nicht auf Fragen.

| Befehl | Nutzlast | Bedeutung | Belegt durch |
|---|---|---|---|
| `01:03` | 1 Byte | Akkustand | Werte `02 → 01`, identisch mit `body[1]` |
| `01:09` | 1 Byte | Lautstaerke | Wert `1f` (31), identisch mit `body[0]`; fuenf Meldungen in einer Sekunde |
| `02:04` | 1 Byte | BassUp | Werte `00 → 01`, identisch mit `body[3]` |
| `0e:09` | 1 Byte | Licht an/aus (vermutet) | kam zweimal mit `01`, jeweils als das Licht angegangen sein duerfte. `00` nie beobachtet |
| `01:04` | 1 Byte | Laedt (vermutet) | kam einmal mit `01`; danach blieb der Akkustand 90 Minuten konstant statt zu fallen. Q4 nennt `01 04` fuer die Space 2 als Lade-Kennzeichen. `00` nie beobachtet |

Die ersten drei stehen in keiner der Quellen Q1–Q6; sie sind aus dem Mitschnitt an
echter Hardware erschlossen. Bei `01:04` deckt sich der Mitschnitt mit Q4.

`01:04` ist der einzige Wert, der in keinem beobachteten Zustandsrahmen auftaucht — alle
Mitschnitte entstanden ohne Netzteil, der Lade-Byte waere also durchweg `00` gewesen und
durch Vergleichen nicht zu finden. Das Profil fuehrt das Feld deshalb mit
`inStateFrame: false`: ohne Offset, nur fuer die Meldung. Einen Offset zu behaupten waere
geraten.

**Belegt (Q2) — der aeltere Boom-2-Mitschnitt.** Die Nutzlast beginnt mit:

Q2 gibt die Bytes dezimal an: `[20, 1, 0, 1, 1, 1, 2, 48, 53, 46, 48, 48, …]`.
Dasselbe in Hex:

```
14 01 00 01 01 01 02 30 35 2e 30 30 …
                     └───────────┘ ASCII "05.00"
```

Daraus und aus den Offsets der Boom 3i (Q3):

| Feld | Offset | Status | Anmerkung |
|---|---|---|---|
| Akku | `body[1]`, Stufe 0..5 | gemeldet (Q3) | im Boom-2-Mitschnitt steht hier `1` |
| `body[0]` | `0` | **Hypothese (meine)** | im Boom-2-Mitschnitt `20` (0x14). `1/5 = 20 %` — koennte Prozent sein, koennte Zufall sein. Deshalb als eigenes Feld sichtbar, nicht stillschweigend als Akkustand verkauft. |
| Firmware | `body[7..12)` ASCII | **belegt (Q2)** | ergibt "05.00". Das einzige Feld, das der echte Boom-2-Mitschnitt bestaetigt. |
| Seriennummer | `body[12..29)` ASCII | gemeldet (Q3) | im Boom-2-Mitschnitt nicht veroeffentlicht |
| BassUp | `body[29]` | gemeldet (Q3) | |
| EQ-Voreinstellung | `body[35]` | gemeldet (Q3) | |
| EQ-Baender | `body[36,39,…,57]` | gemeldet (Q3) | Abstand 3 wirkt ungewoehnlich; zuerst pruefen |
| Lichtmodus | `body[67]` | gemeldet (Q3) | |
| Helligkeit | `body[68]` | gemeldet (Q3) | |
| Licht an/aus | `body[69]` | gemeldet (Q3) | |

Die Firmware-Zeile ist als Unit-Test hinterlegt: `StateDecoderTest` dekodiert genau die
Bytes aus Q2 und prueft, dass "05.00" herauskommt. Alles Synthetische im Testfixture ist
dort als solches markiert.

## Kopplungsliste

**Gemeldet (Q4):** beim Space 2 liefern `0b:01` und `0b:02` Eintraege der
Kopplungsliste, also welche Geraete die Box sonst noch kennt. Beide liegen in der
Lese-Haelfte (`00..7f`) und sind im Profil als READ eingetragen, unbestaetigt.

**Unbekannt:** ein Befehl, der einen Eintrag *entfernt*. Keine Quelle beschreibt einen.
Wer die Liste leeren will, setzt die Box zurueck. Das ist kein Versaeumnis dieser App,
sondern der Stand des oeffentlichen Wissens.

## Schreibende Befehle

Alle **gemeldet (Q3)**, von der Boom 3i, keines an einer Boom 2 geprueft:

| Was | Befehl | Nutzlast |
|---|---|---|
| BassUp | `02 84` | `00` aus / `01` an |
| EQ-Voreinstellung | `02 88` | Voreinstellungs-ID |
| EQ-Band | `02 88` | `fe <maske_hoch> <maske_tief> <gain> <band> 0a` |
| Licht an/aus | `0e 08` | `00` / `01` |
| Helligkeit | `0e 05` | Wert |
| Lichtmodus | `0e 07` | Modus |

**Wichtig:** die Befehlscodes weichen zwischen den Modellen ab. Die Space 2 schreibt den
EQ mit `03 87` (Q4), die Boom 3i mit `02 88` (Q3). Es gibt also keinen gemeinsamen
Befehlssatz, auf den man sich verlassen koennte — nur `01 01` taucht ueberall auf.

**Gemeldet (Q4):** die dB-Kodierung der Space 2 ist `Wert = 120 + dB * 10`, `0x78` also
0 dB. Das Profil nimmt das fuer die Boom 2 an und markiert es als unbestaetigt.

## 02:88 ist der Equalizer - belegt, und zwar auf die unangenehme Art

**Am Geraet bestaetigt (2026-11-06):** `02 88` aendert den Klang. Das ist der erste
schreibende Befehl dieses Projekts mit beobachteter Wirkung, und die Beobachtung kam
zustande, weil die Wirkung schlecht war.

Am 05.11.2026 gingen bei den Schreibversuchen diese zwei Rahmen raus:

```
20 S  08 ee 00 00 00 | 02 88 | 0b 00 | ff                | 8a
21 R  09 ff 00 00 01 | 02 88 | 0a 00                     | 9d   quittiert nach 33 ms
29 S  08 ee 00 00 00 | 02 88 | 10 00 | fe ff ff f0 ff 0a | 85
30 R  09 ff 00 00 01 | 02 88 | 0a 00                     | 9d   quittiert nach 31 ms
```

Die sechs Nutzlast-Bytes lesen sich nach dem Profil so:

| Byte | Bedeutung | gesendet |
|---|---|---|
| `fe` | fester Marker | |
| `ff ff` | Band-Maske | alle Baender |
| `f0` | Verstaerkung | 240 → `(240-120)/10` = **+12,0 dB** |
| `ff` | Band-Index | 255 |
| `0a` | fester Marker | |

Danach berichtete der Nutzer einen hoerbar schlechteren Klang, beschrieben als
ausgefallener Subwoofer. Das passt: +12 dB auf allen neun Baendern treibt den
Verstaerker dauerhaft in den Limiter, und der nimmt zuerst und am meisten den Bass, weil
der Bass die meiste Leistung zieht.

**Warum das mehr als eine Vermutung ist:** die Pruefsumme `85` des Rahmens aus Zeile 29
wurde aus dem Rahmenmodell dieses Projekts *berechnet* und stimmt mit dem Mitschnitt
ueberein, ebenso `8a` fuer Zeile 20. Rahmenaufbau und Zerlegung der Nutzlast sind also
nicht geraten, und ein Test rechnet beide Rahmen nach.

**Ebenfalls belegt:** `02 88` nimmt an derselben Adresse zwei Nutzlastformen an - ein
einzelnes Byte (Voreinstellung) und die sechs Byte oben. Der Index `ff` = 255 wurde
quittiert, obwohl es ihn fast sicher nicht gibt; was die Box damit intern tat, ist
unbekannt.

**Was daraus im Profil folgt:**

- `eqAllBands` ersetzt den frueheren `customEqBand`. Die Maske ist auf `ff ff`
  festgenagelt, der Band-Index auf `ff`, und nur die Verstaerkung ist einstellbar -
  Bereich 60..180 in Schritten von 10, also -6,0 bis +6,0 dB in ganzen Dezibel. `f0`
  ist damit nicht mehr erreichbar, und zwar durch die Allowlist, nicht durch die
  Oberflaeche.
- `eqFlat` ist neu: `02 88` mit `fe ff ff 78 ff 0a`, also alle Baender auf 0,0 dB. 120
  ist der Wert, den im einzigen erfassten Zustandsrahmen alle neun Baender trugen.
- `eqPreset` geht nur noch von 0 bis 9.

**Unbekannt bleibt** die Bit-Belegung der Band-Maske. Ohne sie gibt es keine
Einzelband-Regelung, und Raten ist genau der Fehler oben.

### Der EQ-Block ist eine Liste, keine neun festen Offsets

**Widerlegt am Geraet (2026-11-07)**, vom ersten je empfangenen Zustandsrahmen. Hier
stand bisher: neun Dreiergruppen ab `body[34]`, alle mit `0x78`. Das war aus einem
Mitschnitt erschlossen, in dem zufaellig neun Gruppen drinstanden - und ein Test hat es
behauptet, was es nicht wahrer machte.

| | ab Offset 30 |
|---|---|
| **alter** Mitschnitt, vor allen Schreibversuchen | `ff ff ff 00` + **neun** Gruppen `78 07 0a`, `78 07 0a`, …, `78 06 0a`, `78 01 0a` |
| **neuer** Mitschnitt, nach den Schreibversuchen | `ff ff ff 00` + **eine** Gruppe `78 ff 0a`, dann `0d`, dann Nullen |

Die Nutzlast, die diese App schreibt, ist `fe ff ff 78 ff 0a`. Die eine verbliebene
Gruppe ist also byteweise das, was `eqFlat` zuletzt gesendet hat - Verstaerkung `78`,
Band `ff`, Ende `0a`.

**Daraus folgt dreierlei.** Der Block ist eine **Liste** von Dreiergruppen
(Verstaerkung, Band, `0a`), deren Laenge davon abhaengt, was zuletzt gesetzt wurde.
Das zweite Byte jeder Gruppe ist das **Band-Byte** der Nutzlast - im alten Mitschnitt
stand dort 07/07/07/07/07/07/07/06/01, ungedeutet. Und die Schreibversuche dieser App
haben die neun Eintraege durch einen einzigen ersetzt; die Soundcore-App hat mit
"Signature" die Voreinstellung wiederhergestellt, nicht diese Liste.

**Die beiden Bytes nach `fe`** (`ff ff`) kamen unveraendert zurueck. Sie als "Maske" zu
bezeichnen war eine Vermutung aus dem Boom-3i-Profil; belegt ist nur, dass sie konstant
sind. Variiert wird deshalb das Band-Byte.

**Ab Offset 70** liegt eine andere Struktur: Index-Paare (`01 02`, `02 03`, `03 04`,
`04 01`, `05 06`, `06 03`) und moegliche 16-Bit-Werte mit Vorzeichen (`cd ff` = −51,
`1e 00` = 30, `8e 00` = 142). Sieht nach Filterkoeffizienten aus. **Ungedeutet** - eine
Hypothese aus einem einzigen Rahmen ist keine.

### Wie das Band-Byte gefunden wird, ohne es zu raten

Die Methode steht in `EqProbe` und ist bewusst in Code gegossen statt in gute Vorsaetze
- von Hand Hex zu tippen ist, wie `f0` entstanden ist:

1. `eqFlat`, dann Zustand lesen. Das ist die Grundlinie.
2. `eqProbeBand` mit einem Band-Byte von 0 bis 8. Die Verstaerkung ist als `8c`
   (+2,0 dB) fest in die Nutzlastvorlage geschrieben und ist **kein Argument** - sie
   kann nicht verstellt werden. Hoerbar im Zustandsrahmen, unhoerbar im Raum.
3. Zustand lesen und den **ganzen** Rahmen byte-genau mit der Grundlinie vergleichen -
   nicht nur Offsets, an die das Profil glaubt. Genau dieser Fehler steckte im ersten
   Entwurf: er haette neunmal "nichts bewegt" gemeldet, waehrend die interessanten
   Bytes woanders lagen.
4. Bewegt sich **genau ein** Byte, ist das die Stelle dieses Bandes. Bewegen sich
   mehrere, ist das bei einer Liste normal (ein Eintrag kommt dazu) und wird vollstaendig
   aufgezaehlt; der Schritt ruckt **nicht** vor. Bewegt sich keines, wird der Wert
   ignoriert oder der Befehl kam nicht an; was von beiden, sagt der Mitschnitt.
5. Zurueck zu 1 fuer das naechste Band-Byte.

Offset 1 - der Akkustand - ist vom Vergleich ausgenommen. Er faellt waehrend einer
Sitzung von selbst und wuerde sonst als Ergebnis gemeldet.

**Voraussetzung:** `01 01` muss antworten. In mehreren Mitschnitten tat es das nicht,
und ohne den Zustandsrahmen ist Schritt 3 blind. Deshalb sind es drei getrennte Knoepfe
und kein Automatiklauf: ein Lauf, der nicht beim Scheitern stehenbleibt, erfindet
Ergebnisse.

**`eqProbeBand` ist nicht quittiert.** Diese Nutzlast hat die Box noch nie gesehen.
Quittiert sind nur die Formen mit Maske `ff ff`, und diesen Anspruch auf eine andere
Nutzlast zu uebertragen waere genau die geerbte Behauptung, gegen die dieses Profil
gebaut ist.

## Handshake

**Gemeldet (Q4):** die Space 2 verwirft Schreibzugriffe, bis der Client einen Handshake
gefahren hat (`01:01`, `05:01` mit `01`, mehrfach `05:81`, `18:85` mit `01`).

**Unbekannt** fuer die Boom 2. Das Profil liefert deshalb eine **leere** Handshake-Liste.
Falls deine Box Schreibzugriffe stillschweigend ignoriert, ist das der erste Verdacht:
ein Mitschnitt der Original-App direkt nach dem Verbinden zeigt die noetigen Schritte.
Handshake-Schritte gehen durch dieselbe Policy wie alles andere.

## Klang ueber die Akkuladung

**Am Geraet beobachtet (2026-11-07):** die Sitzung, in der die Box nach Aussage des
Nutzers "ordentlich Dampf" hergab, lief durchgehend bei Akkustand **2/5, dann 1/5** -
also zwischen 20 und 40 Prozent. Das ist die *untere* Grenze, nicht die obere.

**Physik, nicht Firmware:** ein tragbarer Verstaerker laeuft an einer Akkuspannung, die
mit der Ladung faellt. Weniger Spannung heisst weniger Spitzenleistung, heisst der
Schutz-Limiter greift frueher. Kein Protokollbefehl und kein Firmware-Update aendert
daran etwas; mehr Watt entstehen nicht aus Software.

**Was sich messen laesst:** wenn die Box ihre eigene Lautstaerke zuruecknimmt, meldet
sie das ueber `01:09`. Jede fremde Aenderung traegt deshalb jetzt den Akkustand, den die
Box zuletzt gemeldet hat (`ForeignChange.batteryAtChange`). Drei fallende
Lautstaerke-Meldungen hintereinander werden als Selbstschutz benannt, zusammen mit dem
Ladezustand dabei. Greift dagegen nur der Limiter **innerhalb** der DSP, taucht im
Protokoll gar nichts auf - und genau dieser Unterschied ist die Antwort auf "warum wird
es von selbst leiser".

**Was gegen das Problem hilft:** den Klang so einstellen, dass der Limiter auch bei
leerem Akku nicht greift. Dann klingt es bei 100 Prozent genauso wie bei 15 - weil nie
mehr verlangt wird, als der schwaechste Moment hergibt. Das ist die einzige Form von
"ueber die ganze Ladung konstant", die es auf dieser Hardware gibt.

## Was den Steuerkanal vor fremden Geraeten schuetzt - und was nicht

**Am Geraet bestaetigt (2026-11-06):** die Box nimmt den Steuerkanal **ohne
Authentifizierung** an. Alle erfolgreichen Verbindungen der Mitschnitte liefen ueber
`createInsecureRfcommSocketToServiceRecord`; Eintrag 172 eines Mitschnitts zeigt
zusaetzlich eine erfolgreiche Verbindung **mit** Authentifizierung. Sie kann also beides
und verlangt nichts.

**Nicht geklaert:** ob ein **ungekoppeltes** Geraet den Kanal oeffnen kann. Das Handy in
allen Mitschnitten war gekoppelt, also sagt "ohne Authentifizierung" hier nur, dass von
*dieser* Seite nichts gefordert wurde - nicht, dass die Box den Bond nicht braucht. Das
zu pruefen braeuchte ein zweites, nie gekoppeltes Geraet.

**Was eine App auf dem Handy erreichen kann:**

- **Den stark gesicherten Socket bevorzugen.** `SocketPlan` fragt zuerst nach
  Authentifizierung und faellt nur zurueck, wenn sie abgelehnt wird. Das schuetzt *dieses
  Handy* davor, mit einem Betrueger zu reden: wer die Adresse der Box faelscht, aber den
  Kopplungsschluessel nicht hat, kommt durch den authentifizierten Handshake nicht. Es
  schuetzt die Box nicht vor anderen Geraeten.
- **Fremde Aenderungen bemerken.** Die Box meldet Lautstaerke (`01:09`), BassUp (`02:04`)
  und Lichtzustand (`0e:09`) von sich aus. Zieht man davon ab, was die App selbst
  geschickt hat, bleibt ein Protokoll mit Uhrzeit von allem anderen, was die Box
  angefasst hat. `TamperWatch` macht genau das.

**Was nicht geht, und zwar grundsaetzlich:** den Kanal fuer fremde Geraete sperren. Wer
ihn oeffnen darf, entscheidet die Firmware der Box. Keine der Quellen Q1-Q6 beschreibt
einen Befehl, der daran etwas aendert, und Code auf einem Handy kann es ohnehin nicht.
Eine App, die das behauptet, erzeugt ein falsches Sicherheitsgefuehl - der gefaehrlichste
Fehler von allen.

**Was es wirklich erreicht:** Werksreset der Box, danach ausschliesslich ein Geraet
koppeln, und die Kopplungstaste nicht mehr druecken. Dann steht in der Liste der Box ein
Eintrag, und ein neues Geraet kommt nur hinein, wenn jemand physisch an der Box die
Taste haelt.

## Die Box gibt beim Kopplungsmodus zwei Bytes preis - und ihren Empfangspuffer

**Am Geraet beobachtet (2026-11-07).** Ein Zustandsrahmen, gelesen direkt nachdem die
Bluetooth-Taste der Box gedrueckt wurde. Pruefsumme `f8` stimmt, Laengenfeld 156 stimmt -
die Box hat diese Bytes wirklich als einen Rahmen geschickt, hier ist nichts
falsch zusammengefuegt.

**Sechzehn Bytes unterscheiden sich** vom Rahmen 36 Minuten davor, und sie zerfallen in
zwei voellig verschiedene Befunde.

### Zwei Kennzeichen kippen

| Byte | vorher | nachher |
|---|---|---|
| 2 | `00` | `01` |
| 67 | `00` | `01` |

Beide waren in **jedem** frueheren Rahmen `00`. Byte 2 liegt im Block der
Betriebszustaende, direkt neben Lautstaerke (0) und Akku (1), was fuer Byte 2 spricht.
**Belegt ist nichts davon** - es ist eine einzige Beobachtung, und beide stehen deshalb
als unbestaetigte Felder im Profil (`pairingOpen`, `unknownFlag67`). Eines als
"Kopplungsmodus" zu benennen und das andere zu verschweigen waere geraten.

Der Test, der es entscheidet, steht in `PairingWatch.TEST`. Der wichtige Schritt ist der
letzte: **ein Byte, das nach dem Beenden des Kopplungsmodus auf `01` stehenbleibt, war
es nicht.**

### Die Box gibt ihren Empfangspuffer heraus

Bytes 38 bis 59, wo frueher Nullen standen:

```
0d 02 0a 00 0d | 08 ee 00 00 00 0b 02 0a 00 | 0d 08 ee 00 00 00 0b 01 0a 00
```

Darin stecken zwei **vollstaendige ausgehende Rahmen** - Header `08 ee 00 00 00`, Befehle
`0b 02` und `0b 01`, Laenge `0a 00`. Das sind die Kopplungslisten-Abfragen, die *diese
App* geschickt hat - aber **nicht in dieser Sitzung**: der Mitschnitt hat fuenf
Eintraege und kein einziges `SENT` mit `0b`.

Die Box gibt also Inhalt ihres **Empfangspuffers aus einer frueheren Sitzung** in ihre
Zustandsantwort hinein, in einem Bereich, der zuvor als Nullen gelesen wurde. Das ist
ein Firmware-Fehler der Box: ein nicht geloeschter Puffer, dessen Reste an jedes
verbundene Geraet zurueckfliessen. Was dort landet, bestimmt nicht diese App.

**Zwei Folgen.** Erstens: `0d` an Byte 37 ist damit vermutlich das Ende der EQ-Liste und
alles danach ist Altlast, nicht Nutzdaten - die Deutung des EQ-Blocks von oben bleibt,
wird aber durch dieses Byte begrenzt. Zweitens: ein Vergleich zweier Zustandsrahmen darf
sich auf diesen Bereich nicht verlassen. `EqProbe` vergleicht den ganzen Rahmen und
meldet jede Abweichung einzeln, was hier genau richtig ist - es haette diese sechzehn
Bytes aufgelistet statt eine Erkenntnis daraus zu machen.

**Nicht behebbar von hier.** Der Puffer liegt in der Box.

## Kopplungsmodus abschalten geht nicht

**Was nicht geht:** der Box das Kopplungssignal entziehen. Die Bluetooth-Taste macht die
Box sichtbar, das ist ihre Aufgabe, und sie liegt in der Firmware. Um das zu unterbinden,
muesste man ihr Funkmodul am Senden hindern oder die Taste abschalten - beides kann nur
die Firmware. Keine der Quellen Q1-Q6 beschreibt einen Befehl dafuer.

**Und warum hier nicht geraten wird:** die Kopplungs-Kategorie ist `0b`. Ein geratener
Schreibbefehl darin trifft im besten Fall nichts und im schlechtesten die Kopplungsliste
- und der Nutzer sperrt sein eigenes Handy aus. Lesebefehle (`00..7f`) sind harmlos und
duerfen probiert werden; Schreibbefehle (`80..ff`) in dieser Kategorie nicht.

**Was wirklich hilft:** die Taste nicht druecken. Das klingt banal, ist aber der
Mechanismus - solange die Box in Benutzung ist, kommt ein neues Geraet nur hinein, wenn
jemand physisch an der Box diese Taste haelt. Dazu Werksreset plus ausschliesslich ein
gekoppeltes Geraet, und, falls die Box es anbietet, Multipoint in der Soundcore-App aus.

**Was die App beitraegt:** `PairingWatch` liest den Zustand alle zehn Sekunden neu und
warnt, wenn eines der beiden Kennzeichen auf `01` steht. Das verhindert nichts, aber es
macht aus "jemand koennte sich einwaehlen, wenn ich nicht hinsehe" ein "mein Handy hat
es mir gesagt". Die Warnung nennt sich ausdruecklich einen Verdacht, solange der Test
oben nicht gelaufen ist - eine Warnung, die zu oft faelschlich schreit, wird bei der
ersten echten ignoriert.

## Firmware und OTA

**Unbekannt.** Keine Quelle nennt einen Firmware- oder OTA-Befehl fuer irgendein
Soundcore-Geraet. Darum ist `blockedCommands` im Profil leer — und darum ist die Policy
so gebaut, dass das keine Luecke ist: Default-Deny blockt jeden Befehl, der nicht in der
Allowlist steht, also auch alle OTA-Befehle, die noch niemand gefunden hat. Wenn du beim
Kartografieren einen findest, gehoert er zusaetzlich in `blockedCommands`, damit er auch
im Experten-Modus gesperrt bleibt.

## Zusammenfassung des Verifikationsstands

- **An echter Hardware bestaetigt:** Transport (RFCOMM), Modellnummer A3138, Dienst-UUID
  `0cf12d31-fac3-4553-bd80-d6832e7b3138`, **der komplette Rahmen** (Header, Laengenfeld
  als Gesamtlaenge, Pruefsumme), `01 01` als Zustandsabfrage, und die Felder
  Lautstaerke, Akku, BassUp, Firmware, Seriennummer sowie die Lage der neun EQ-Baender.
  Dazu die drei Meldungen `01:03`, `01:09` und `02:04`, die in keiner Quelle stehen.
- **An echter Hardware bestaetigt (2026-11-06):** die Box bietet mehrere
  herstellerspezifische Dienste an, und nur `…7b3138` antwortet. `…7c3138` nimmt die
  Verbindung an und schweigt, `df21fe2c-…-f12c4d67927c` laesst sich nicht verbinden.
- **Mit beobachteter Wirkung belegt (2026-11-06):** `02 88` ist der Equalizer. Belegt
  durch eine Verschlechterung, nicht durch eine Verbesserung - siehe den eigenen
  Abschnitt. Der einzige schreibende Befehl mit `verified: true`.
- **Von der Box quittiert, Wirkung ungeprueft:** `02:84`, `0e:08`, `0e:05`. Die Box
  kennt diese Adressen; dass ihre Namen stimmen, ist weiter vom Boom 3i uebernommen.
- **Gemeldet, plausibel, ungeprueft:** die Licht-Offsets, die Deutung von `0e:09`, die
  Deutung von `01:04` als Lade-Kennzeichen (deckt sich mit Q4), die Kopplungslisten-
  Abfragen `0b:01`/`0b:02` (von dieser Box nie beantwortet), und der Lichtmodus-Befehl
  `0e:07`, der als einziger nicht quittiert wurde.
- **Widerlegt:** `0cf12d31-…-d6832e7d3138` als UUID der Boom 2, und damit die Annahme,
  das Byte vor der Modellnummer sei fuer alle Soundcore-Geraete gleich. Ebenfalls
  widerlegt: dass das Hersteller-Praefix den Steuerkanal eindeutig bestimmt.
- **Hypothese:** das UUID-Schema als solches, `body[0]` als Akku-Prozent, der Zweck von
  `…7c3138`, die Zuordnung der EQ-Voreinstellungs-IDs zu Namen.
- **Unbekannt:** die Bit-Belegung der EQ-Band-Maske, warum `01 01` in manchen Sitzungen
  auch ueber `…7b3138` unbeantwortet bleibt, der gueltige Wertebereich von `0e:07`, die
  OTA-Befehle, ein Befehl der die Kopplungsliste aendert, und wo das Lade-Kennzeichen im
  Zustandsrahmen steht (falls ueberhaupt).

Deshalb traegt in der App jedes nicht verifizierte Feld ein rotes **unbestaetigt**, und
deshalb ist der Nur-Lesen-Modus die Voreinstellung. Der Equalizer-Vorfall zeigt, warum
beides nicht genuegt: der Nur-Lesen-Modus war ausgeschaltet, weil geschrieben werden
sollte, und "unbestaetigt" sagt nichts darueber, wie weit ein Regler reichen darf.
Dafuer sind jetzt die Argumentbereiche da.
