# Schichtpuls (Android)

Dienstplan-App für die Pflege: Stationsplan fotografieren, Dienste erkennen lassen, prüfen und
im eigenen Kalender pflegen. Mehrere Schichten pro Tag, frei änderbare Zeiten, Netto-Stunden.

- `web/schichtpuls.html` ist die gemeinsame Oberfläche (dieselbe Datei läuft auch auf claude.ai).
- `tools/wrap.py` packt sie vor dem Build nach `app/src/main/assets/index.html`.
- `MainActivity` zeigt sie in einer WebView und liefert Fotoauswahl, Kamera, Speichern in
  Downloads und externe Links.
- Daten bleiben auf dem Handy (localStorage). Die Foto-Erkennung läuft auf dem Gerät
  (Google ML Kit): kostenlos, offline, ohne API-Schlüssel.
- Projektstand, Entscheidungen und nächste Schritte: `CLAUDE.md`.
- Test der Dienstplan-Erkennung: `node tools/test_parser.cjs`.

Jeder Push auf `schichtpuls-android/**` baut die APK per GitHub Actions und legt sie im Release
`schichtpuls-latest` ab:
https://github.com/Micha8326/liqstorm-web/releases/download/schichtpuls-latest/Schichtpuls.apk

Die APK ist mit `app/schichtpuls-sideload.jks` signiert, damit neue Versionen über die alte
installiert werden können.
