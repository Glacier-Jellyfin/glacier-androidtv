# Changelog (Deutsch)

The German release notes, shown in the app when its language is German.
Every version section mirrors the one in [CHANGELOG.md](CHANGELOG.md), with
these subsections in this order: `### Neu`, `### Verbessert`, `### Behoben`,
`### Bekannte Probleme`. Versions before 0.6.3 exist only in English.

## [Unreleased]

## [0.7.1] - 2026-10-31

### Neu
- Einstellungen › System › Wiedergabe prüfen spielt kurze Testdateien ab und zeigt, welche Bild- und Tonformate das Gerät schafft. Die Ergebnisse kommen für Fehlerberichte ins Protokoll

### Verbessert
- Zurück von einem Titel aus „Ähnliche Titel“ führt in die Bibliothek oder Suche, in der du angefangen hast, mit dem letzten Titel im Fokus
- Logs für Fehlerberichte beschreiben Decoder und Tonausgänge des Geräts und jede Wiedergabe genauer

### Behoben
- Links aus der A–Z-Leiste einer Bibliothek landet auf dem Titel, zu dem der Buchstabe gesprungen ist, statt auf dem zuvor gewählten
- Dolby-Vision-Titel mit Enhancement Layer laufen direkt in HDR10, wenn das Gerät die Ebene nicht dekodieren kann, statt zu scheitern (#21)
- Kann das Gerät Bild oder Ton einer Datei nicht dekodieren, sagt der Player das, statt zur Prüfung des Servers aufzufordern (#21)

## [0.7.0] - 2026-10-29

### Neu
- Die Einstellungen jedes Profils werden auf seinem Jellyfin-Server gespeichert, einmal pro Gerät. Ist ein Profil neu auf einem Gerät (nach einer Neuinstallation, beim Wechsel zur Version aus dem Play Store oder Amazon Appstore oder auf einem anderen Fernseher), bietet Glacier einmal an, die Einstellungen eines bekannten Geräts zu übernehmen. Unter Einstellungen › Konto › Gespeicherte Einstellungen geht das auch später. Dort lassen sich auch nicht mehr gebrauchte Stände löschen, etwa der einer alten Installation
- Einstellungen › Konto › Von diesem Gerät entfernen löscht Anmeldung, PIN, Jugendschutz und Einstellungen eines Profils auf diesem Fernseher, auf Wunsch auch seine Einstellungen auf dem Server. Das Jellyfin-Konto bleibt bestehen

### Verbessert
- Der Überspringen-Button blendet in einer Sekunde statt zweieinhalb aus, und sein Rahmen verschwindet mit ihm, statt als Letztes stehen zu bleiben

## [0.6.3] - 2026-10-28

### Verbessert
- Der Update-Dialog auf der Startseite lässt sich unter Einstellungen › System abschalten. Eine neue Version erscheint dort weiter und als Punkt am Zahnrad der Einstellungen
