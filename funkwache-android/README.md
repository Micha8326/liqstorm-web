# Funkwache

Android-App, die alles auswertet, was ein Handy an Funk empfangen darf – Bluetooth (LE + klassisch),
WLAN, Mobilfunk, GNSS/GPS, NFC und das Magnetfeld – und daraus Bedrohungen erkennt.
Ein Hintergrund-Wächter überwacht dauerhaft, die Scanner-Ansichten durchsuchen die Umgebung.

Keine Internet-Berechtigung: alle Daten bleiben auf dem Gerät.

## Was erkannt wird

| Bereich | Erkennung |
|---|---|
| **Tracker** | AirTag/Find My (Offline-Modus), Samsung SmartTag, Tile, Chipolo, Google Find Hub, DULT-Standard. Alarm, wenn ein Tracker dir über Zeit **und** Strecke folgt (≥ 10 min, ≥ 300 m) |
| **Verfolgung allgemein** | Beliebige Bluetooth-Geräte, die über ≥ 1 km mitreisen (eigene Geräte als „vertraut“ markieren) |
| **Bluetooth-Angriffe** | BLE-Spam (Flipper Zero / ESP32 Pop-up-Flut), Flipper Zero per OUI/Name/Service, Kamera-Brillen (Meta) |
| **WLAN** | Evil Twin (bekanntes Netz plötzlich offen), gleiches Netz offen + verschlüsselt, Karma/WiFi Pineapple, Pentest-Hardware (Hak5, ALFA), Angriffs-Tool-Namen, typische Kamera-Netze, WEP/offen/WPS, Proxy auf der Verbindung |
| **Mobilfunk (IMSI-Catcher-Indizien)** | Downgrade 4G/5G → 2G, bekannte Zell-ID mit neuer LAC/TAC, häufige Gebietswechsel im Stillstand, extrem starke Zelle, fremdes Netz ohne Roaming |
| **GNSS** | Spoofing (gleich starke Satellitensignale, unplausible Positionssprünge), Jamming (plötzlicher Totalausfall) |
| **System** | Bildschirmsperre, Patchstand, Root, USB-/WLAN-Debugging, Bluetooth sichtbar, Nutzer-CA-Zertifikate, Proxy, VPN, privates DNS, Apps mit Bedienungshilfe-/Benachrichtigungs-/Geräteadmin-Zugriff, versteckte Apps außerhalb eines Stores mit vielen Überwachungsrechten (Stalkerware-Muster). **Änderungen** gegenüber dem letzten Stand lösen Alarm aus (neue Admin-App, neues Zertifikat, neu gekoppeltes Gerät …) |

## Umgebung absuchen

- **Bluetooth**: Liste aller Geräte mit Typ, Hersteller, Signal, Entfernungsschätzung, Rohdaten; Filter (Tracker, Risiko, nah); klassische Suche.
- **Orten**: Geigerzähler-Modus für jedes BT-Gerät/WLAN – Piepton wird schneller, je näher du kommst.
- **WLAN**: Verbindungsdetails, alle Netze mit Sicherheit/Kanal/Standard, Kanalbelegung.
- **Funk**: Mobilfunkzellen (versorgend + Nachbarn), Satelliten pro System mit C/N0, NFC-Tag-Leser, Magnet-Sonde.
- **Bericht teilen**: kompletter Umgebungsbericht als Text.

## Grenzen

Android erlaubt keinen Zugriff auf Rohfunk. Nicht messbar: WLAN-Deauth-Frames, Sub-GHz (433/868 MHz),
UWB, Funk-Wanzen ohne WLAN/Bluetooth. IMSI-Catcher- und Spoofing-Erkennung arbeiten mit Indizien.
iOS erlaubt keine WLAN-/Zell-Scans – deshalb nur Android (ab 8.0).

## Installieren

GitHub › Actions › „Funkwache Android“ › letzter Lauf › Artefakt `funkwache-apk` herunterladen, entpacken,
`app-release.apk` aufs Handy und installieren („Unbekannte Apps installieren“ erlauben).
Beim ersten Start alle Berechtigungen erteilen, Standort und Bluetooth einschalten, dann auf „Schutz“
den Hintergrund-Wächter aktivieren und die App von der Akku-Optimierung ausnehmen.

Das APK ist mit einem pro Build erzeugten Debug-Schlüssel signiert: Für ein Update ggf. vorher deinstallieren.

## Selbst bauen

```
cd funkwache-android
./gradlew testDebugUnitTest assembleRelease
```
