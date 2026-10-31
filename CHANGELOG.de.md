# Changelog (Deutsch)

The German release notes, shown in the app when its language is German.
Every version section mirrors the one in [CHANGELOG.md](CHANGELOG.md), with
these subsections in this order: `### Neu`, `### Verbessert`, `### Behoben`,
`### Bekannte Probleme`. Versions before 0.6.3 exist only in English.

## [Unreleased]

### Neu
- Bibliotheken mit Heimvideos und Fotos bekommen einen eigenen Eintrag in der Navigationsleiste, Fotos & Videos. Du blätterst sie nach Ordnern durch, die Ordner getrennt von Videos und Fotos und der Pfad über dem Titel. Die übrigen Videos eines Ordners laufen danach eins nach dem anderen
- Fotos und Videos öffnen sich im Vollbild, ein Video läuft gleich dort. Links und rechts blättern mit animiertem Übergang durch den Ordner, OK auf einem Foto startet oder stoppt eine Diashow, OK auf einem Video öffnet den Player an der aktuellen Stelle. Eine Linie am unteren Rand zeigt, wie weit ein Video gelaufen ist oder wie lange die Diashow ein Foto noch zeigt. Unter Einstellungen › Wiedergabe stellst du ein, wie lange sie jedes Foto zeigt
- Ein Ordner zeigt seine Videos und Fotos als Karten oder als Galerie: Das gewählte Bild füllt den Bildschirm hinter Titel und Ordnern, unten liegt eine Zeile kleiner Bilder zum Durchklicken. Gehst du in die Zeile, kommt das ganze Bild über der Zeile nach vorn, die Navigationsleiste fährt weg, und ein Video spielt darin. Chips links zeigen alle, nur Videos oder nur Fotos, Menüs rechts wählen die Ansicht und sortieren nach Aufnahmedatum oder Name. Zurück springt zuerst zu den Chips. Nach Datum sortiert bekommt jeder Monat unter den Karten eine Überschrift, nach Name sortiert springt eine A–Z-Leiste zu einem Buchstaben. Die Vollbildansicht blättert auch durch die Videos. Unter Einstellungen › Darstellung haben die Karten 16:9 oder 4:3

### Verbessert
- Die Navigationsleiste lässt oben rechts Platz für Hinweise. Brauchen ihre Einträge mehr Platz, scrollen die zwischen Suche und Einstellungen, und Suche, Einstellungen und Profil bleiben immer sichtbar
- Der Player nennt Musikvideos und Aufnahmen nicht mehr „Filme“
- Zurück von einem Titel aus „Ähnliche Titel“ führt in die Bibliothek oder Suche, in der du angefangen hast, auch nach einem Umweg über die Besetzung. Besetzung, Episoden und Alben führen weiter zurück zu ihrem Titel

### Behoben
- Rechts vom Profil in der Navigationsleiste springt der Fokus nicht mehr hinunter zur A–Z-Leiste einer Bibliothek
- Links aus der A–Z-Leiste einer Bibliothek landet auf dem Titel, zu dem der Buchstabe gesprungen ist, statt auf dem zuvor gewählten
- Dolby-Vision-Titel in einem Profil, das das Gerät nicht dekodieren kann, wandelt der Server um, statt dass die Wiedergabe scheitert (#21)
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
