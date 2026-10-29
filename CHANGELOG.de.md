# Changelog (Deutsch)

The German release notes, shown in the app when its language is German.
Every version section mirrors the one in [CHANGELOG.md](CHANGELOG.md), with
these subsections in this order: `### Neu`, `### Verbessert`, `### Behoben`,
`### Bekannte Probleme`. Versions before 0.6.3 exist only in English.

## [Unreleased]

### Neu
- Die Einstellungen jedes Profils werden auf seinem Jellyfin-Server gespeichert, einmal pro Gerät. Ist ein Profil neu auf einem Gerät (nach einer Neuinstallation, beim Wechsel zur Version aus dem Play Store oder Amazon Appstore oder auf einem anderen Fernseher), bietet Glacier einmal an, die Einstellungen eines bekannten Geräts zu übernehmen. Unter Einstellungen › Konto › Gespeicherte Einstellungen geht das auch später. Dort lassen sich auch nicht mehr gebrauchte Stände löschen, etwa der einer alten Installation
- Einstellungen › Konto › Von diesem Gerät entfernen löscht Anmeldung, PIN, Jugendschutz und Einstellungen eines Profils auf diesem Fernseher, auf Wunsch auch seine Einstellungen auf dem Server. Das Jellyfin-Konto bleibt bestehen

### Verbessert
- Der Überspringen-Button blendet in einer Sekunde statt zweieinhalb aus, und sein Rahmen verschwindet mit ihm, statt als Letztes stehen zu bleiben

## [0.6.3] - 2026-10-28

### Verbessert
- Der Update-Dialog auf der Startseite lässt sich unter Einstellungen › System abschalten. Eine neue Version erscheint dort weiter und als Punkt am Zahnrad der Einstellungen
