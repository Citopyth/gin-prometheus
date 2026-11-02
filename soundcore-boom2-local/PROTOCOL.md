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
die Rede. Deshalb benutzt diese App ausschliesslich RFCOMM — was zugleich die
Angriffsfläche klein haelt und den Scan (und damit die Standort-Berechtigung) erspart.

**Belegt (Q4):** die UUID ist **nicht** die Standard-SPP-UUID, sondern herstellerspezifisch.
Fuer die Space 2 (Modell D1402): `0cf12d31-fac3-4553-bd80-d6832e7d1402`, RFCOMM-Kanal 30.

**Gemeldet (Q3):** die Boom 3i benutze eine "D5100-RFCOMM-UUID". Die Zeichenkette selbst
steht dort nicht.

**Hypothese (meine):** die letzten vier Hex-Stellen der UUID sind die Modellnummer —
`…d6832e7d` + `1402` fuer D1402, also `…d6832e7d5100` fuer D5100. Fuer die Boom 2 waere
das `0cf12d31-fac3-4553-bd80-d6832e7d3138`, wenn die Boom 2 wirklich A3138 ist (das ist
eine unbestaetigte Nutzerangabe in Q3).

**Warum das nicht wichtig ist:** die App raet die UUID nicht. Sie liest den SDP-Eintrag
der Box (`BluetoothDevice.uuids`) und nimmt den Dienst, der mit dem Praefix
`0cf12d31-fac3-4553-bd80-d6832e7d` anfaengt. Nur wenn die Box *nichts* Passendes
anbietet, probiert sie die Kandidaten aus dem Profil. Die Hypothese ist also der
Rueckfall, nicht die Grundlage.

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

**Unbekannt:** ob das Laengenfeld den ganzen Rahmen oder nur die Nutzlast zaehlt. Die App
nimmt "ganzer Rahmen" an (`LengthSemantics.TOTAL`), kann aber beides, und
`CaptureValidator` entscheidet es aus echten Bytes statt aus der Annahme. Genau dafuer
ist der Reiter *Protokoll* da.

## Zustand lesen

**Belegt (Q2, Q3, Q4):** Befehl `01 01` fragt bei Space 2, Boom 3i **und Boom 2** den
Zustand ab. Das ist die einzige Protokoll-Aussage, die quer ueber alle drei Geraete
zusammenpasst.

**Belegt (Q2) — der echte Boom-2-Mitschnitt.** Die Nutzlast beginnt mit:

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

## Handshake

**Gemeldet (Q4):** die Space 2 verwirft Schreibzugriffe, bis der Client einen Handshake
gefahren hat (`01:01`, `05:01` mit `01`, mehrfach `05:81`, `18:85` mit `01`).

**Unbekannt** fuer die Boom 2. Das Profil liefert deshalb eine **leere** Handshake-Liste.
Falls deine Box Schreibzugriffe stillschweigend ignoriert, ist das der erste Verdacht:
ein Mitschnitt der Original-App direkt nach dem Verbinden zeigt die noetigen Schritte.
Handshake-Schritte gehen durch dieselbe Policy wie alles andere.

## Firmware und OTA

**Unbekannt.** Keine Quelle nennt einen Firmware- oder OTA-Befehl fuer irgendein
Soundcore-Geraet. Darum ist `blockedCommands` im Profil leer — und darum ist die Policy
so gebaut, dass das keine Luecke ist: Default-Deny blockt jeden Befehl, der nicht in der
Allowlist steht, also auch alle OTA-Befehle, die noch niemand gefunden hat. Wenn du beim
Kartografieren einen findest, gehoert er zusaetzlich in `blockedCommands`, damit er auch
im Experten-Modus gesperrt bleibt.

## Zusammenfassung des Verifikationsstands

- **Belegt und getestet:** Transport (RFCOMM), Vendor-UUID-Schema, Rahmenaufbau in der
  Struktur, `01 01` als Zustandsabfrage, Firmware-Offset der Boom 2.
- **Gemeldet, plausibel, ungeprueft:** alle uebrigen Offsets und alle Schreibbefehle.
- **Hypothese:** die letzten vier UUID-Stellen, `body[0]` als Akku-Prozent.
- **Unbekannt:** Laengenfeld-Semantik, Handshake, OTA-Befehle, Zuordnung der
  EQ-Voreinstellungs-IDs zu Namen.

Deshalb traegt in der App jedes nicht verifizierte Feld ein rotes **unbestaetigt**, und
deshalb ist der Nur-Lesen-Modus die Voreinstellung.
