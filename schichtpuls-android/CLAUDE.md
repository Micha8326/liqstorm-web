# Schichtpuls: Projektstand und Übergabe

Persönliche Dienstplan-App für einen Krankenpfleger (Android). Dieses Dokument ist der
aktuelle Stand für jede neue Claude-Code-Sitzung. **Zuerst lesen.**

Stand: 9. Oktober 2026

## Wo der Code liegt

- Repo `Micha8326/liqstorm-web`, **Branch `ccr-3bd28fe2-yrq2gr`**, Ordner `schichtpuls-android/`.
  Auf `main` ist dieser Ordner **nicht**. Am PC zuerst:
  `git fetch origin && git checkout ccr-3bd28fe2-yrq2gr`
- Die App liegt nur vorübergehend in diesem Repo, weil die Cloud-Sitzung kein neues GitHub-Repo
  anlegen durfte. **Die App hat nichts mit Liqstorm zu tun.** Der Nutzer will sie komplett
  eigenständig. Geplant: Umzug in ein eigenes, leeres Repo (z. B. `dienstplan`), sobald der
  Nutzer es angelegt hat, danach alles Schichtpuls-Bezogene aus `liqstorm-web` entfernen
  (siehe "Offen").

## Feste Entscheidungen des Nutzers

1. **Eigenständige App.** Eigener Name (Schichtpuls), eigenes Symbol, kein Liqstorm-Branding,
   nicht an liqstorm.com gebunden. flowmedical-systems.de (seine Medizin-Website, IONOS) wäre
   höchstens als Ort denkbar, ist aber nicht gewünscht.
2. **Keine Zusatzkosten, kein API-Schlüssel.** Keine Anthropic-API, kein SDK in der Android-App.
   Die Fotoerkennung läuft auf dem Handy (Google ML Kit, kostenlos, offline). Nicht wieder
   einbauen.
3. **Richtige App auf dem Homescreen** (installierbare APK), nicht nur ein Web-Link.
4. **Zugriff auf vorhandene Fotos** aus der Galerie und neue Fotos mit der Kamera.
5. **Variabel:** mehrere Schichten pro Tag, abweichende Zeiten pro Tag, eigene Dienstarten.
6. Optik: dunkel, futuristisch, Cyan-Leuchten, im Stil seiner Seite flowmedical-systems.de.
7. Sprache der App und der Kommunikation: Deutsch.

## Aufbau

```
schichtpuls-android/
  web/schichtpuls.html     gemeinsame Oberfläche (HTML/CSS/JS in einer Datei), Quelle der Wahrheit
  tools/wrap.py            packt web/schichtpuls.html nach app/src/main/assets/index.html (vor jedem Build)
  tools/test_parser.cjs    Test der Dienstplan-Erkennung mit künstlichen OCR-Daten: node tools/test_parser.cjs
  app/src/main/java/de/schichtpuls/app/MainActivity.kt
                           WebView-Hülle: Fotoauswahl (Android Photo Picker), Kamera, ML-Kit-Texterkennung,
                           Speichern in Downloads, externe Links im Browser
  app/schichtpuls-sideload.jks
                           fester Signaturschlüssel, damit neue APKs über alte installiert werden können
.github/workflows/schichtpuls-android.yml
                           baut bei jedem Push auf schichtpuls-android/** die APK und legt sie im Release
                           "schichtpuls-latest" ab
```

- Android-Werkzeuge (SDK) sind in der Cloud-Sitzung gesperrt, gebaut wird nur über GitHub Actions.
  Download der fertigen APK:
  https://github.com/Micha8326/liqstorm-web/releases/download/schichtpuls-latest/Schichtpuls.apk
- Die APK enthält nur `arm64-v8a`, damit sie klein bleibt (ca. 16 MB statt 44 MB).
- Zwei Betriebsarten derselben `web/schichtpuls.html`:
  - **Android-App** (`window.SchichtpulsApp` vorhanden): Daten in localStorage auf dem Handy,
    Fotoerkennung über `SchichtpulsApp.ocr()` (ML Kit) und `parseRoster()` im Browser-Code,
    Dateien über `SchichtpulsApp.saveFile()` nach Downloads.
  - **claude.ai-Artifact** (`window.claude` vorhanden): https://claude.ai/artifact/DWnY6mThnCwZhaJJtv7ZRK
    Daten privat in der Artifact-Datenbank des Nutzers, Erkennung über die `sample`-Fähigkeit
    (läuft über sein Claude-Abo). Dieselbe Datei wird dort veröffentlicht. Ist nur Nebenschiene, die
    Hauptsache ist die Android-App.

### Datenmodell (localStorage `schichtpuls.v1`)

```
{ name, codes: [{code, label, start, end, pause, color, kind: work|free|absent}],
  months: { "2026-11": { "04": { shifts: [{code, start?, end?}], note?, src: manual|scan } } } }
```

`start`/`end` stehen nur in einer Schicht, wenn sie von der Standardzeit der Dienstart abweichen.
Ältere Einträge `{code, start, end}` liest `shiftsOf()` weiterhin.

### Fotoerkennung (Android)

1. Foto wird hochkant gedreht (EXIF), auf max. 3200 px verkleinert, als JPEG an `SchichtpulsApp.ocr`.
2. ML Kit liefert Wörter mit Positionen `{t,x,y,w,h}`.
3. `parseRoster()` findet die Zeile mit den Tagesnummern 1–31 (verträgt Schräglage, fehlende Zahlen,
   zwei Hälften nebeneinander), den Namen (unscharf, Umlaute), und ordnet jedes Wort auf der
   Namenszeile der nächsten Tagesspalte zu. Typische OCR-Verwechslungen (5/S, 0/O, 8/B) werden
   zurückgeführt. Ohne Tageszahlen: Zuordnung der Reihe nach, alles als unsicher markiert.
4. Kein Treffer: dasselbe Foto um 90° und 270° gedreht erneut.
5. Prüfschritt: Nutzer korrigiert, unsichere Tage sind gelb markiert.

Grenzen: Handschrift wird von ML Kit schlecht gelesen. Mit echten Fotos ist die Erkennung **noch
nicht getestet**, nur mit künstlichen Daten (`tools/test_parser.cjs`, alle grün).

## Verlauf (kurz)

1. Erste Web-App unter `liqstorm-web/dienstplan/` (PR #1, gemergt). Nutzer will das **nicht** bei
   Liqstorm; liqstorm.com ist bei IONOS ohnehin nicht verbunden (SSL-Fehler).
2. Eigenständige Version als claude.ai-Artifact (Schichtpuls), Pflege-Dienstarten, Netto-Stunden.
3. Mehrere Schichten pro Tag, freie Zeiten, Dienstarten direkt im Tag anlegen.
4. Foto-Knöpfe repariert (waren gesperrt, jetzt native `<label for>`).
5. Android-App gebaut (WebView + Photo Picker + Kamera).
6. API-Schlüssel wieder entfernt (Nutzer will nicht zusätzlich zahlen), stattdessen ML Kit auf dem Gerät.
7. APK auf arm64 verkleinert und dem Nutzer geschickt.

## Offen / nächste Schritte

- [ ] Rückmeldung des Nutzers: Installation, Fotoauswahl, Kamera und Erkennung mit einem echten
      Foto seines Stationsplans. Danach `parseRoster()` an das echte Layout anpassen.
- [ ] Eigenes Repo: Nutzer legt ein leeres GitHub-Repo an, dann `schichtpuls-android/` und den
      Workflow dorthin umziehen (Workflow-Pfade anpassen).
- [ ] Danach in `liqstorm-web` aufräumen (nur nach Umzug und mit Zustimmung des Nutzers):
      Branch `ccr-3bd28fe2-yrq2gr`, Release + Tag `schichtpuls-latest`, Workflow
      `.github/workflows/schichtpuls-android.yml` und den alten Ordner `dienstplan/` auf `main`.
- [ ] Optional: Erinnerungen (Benachrichtigung vor Dienstbeginn), Monatsübersicht für Soll/Ist-Stunden.

## Arbeitsweise

- Änderungen an der Oberfläche nur in `web/schichtpuls.html`, danach `python3 tools/wrap.py`.
- Vor dem Push: `node tools/test_parser.cjs` muss grün sein.
- Commit/Push auf den Branch oben löst den APK-Build aus. Die neue APK liegt nach ca. 5 Minuten im
  Release `schichtpuls-latest`.
