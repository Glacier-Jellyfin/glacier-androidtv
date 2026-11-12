# Changelog (Deutsch)

The German release notes, shown in the app when its language is German.
Every version section mirrors the one in [CHANGELOG.md](CHANGELOG.md), with
these subsections in this order: `### Neu`, `### Verbessert`, `### Behoben`,
`### Bekannte Probleme`. Versions before 0.6.3 exist only in English.

## [Unreleased]

### Verbessert
- Die App lässt sich auf Geräten, die es unterstützen, auf externen Speicher verschieben
- Die Wiedergabe misst die Verbindung und passt einen Transcode daran an, damit es bei einer langsameren Leitung nicht ruckelt
- Ändert sich die Verbindung, wechselt die Wiedergabe ohne Unterbrechung zwischen der Datei und kleineren Bildern (4K, 1080p, 720p)
- Transkodiertes Video baut einen großen Puffer auf und stockt deutlich seltener
- Ein Transcode wird für die Größe des Displays erzeugt
- Ein langsamer Server beendet die Wiedergabe nicht mehr mit einem Fehler
- Neu gestalteter Dialog bei Wiedergabefehlern
- Ist die Leitung für eine Datei zu langsam, wandelt der Server nur den Ton um und behält das Bild, wo es passt
- Fortsetzen startet schneller und beginnt dafür eventuell einige Sekunden vor dem gespeicherten Stand
- Das Infofeld zeigt einen bevorstehenden Wechsel zwischen Strömen und seinen Grund
- Der Countdown für Überspringen und die nächste Folge läuft flüssig

### Behoben
- „Untertitel aus“ auf der Detailseite gilt auch für die nächste Folge
- „Als Nächstes“ wartet, solange nach dem Abspann noch eine Szene folgt

## [0.9.0] - 2026-11-05

### Neu
- Spanische Oberfläche

### Verbessert
- Dolby Vision Profil 7 aus Dual-Layer-MKV-Dateien läuft in Dolby Vision
- Ton für den Receiver startet von selbst neu, wenn der Fernseher ihn nicht mehr abspielt
- Kurze HDMI-Aussetzer schalten den Ton nicht mehr vom Receiver auf das Gerät um
- Einstellungen › System öffnet den Update-Dialog, in dem sich lange Versionshinweise bis zum Ende lesen lassen

### Behoben
- Einige Dolby-Vision-MKV-Dateien brechen nicht mehr mit einem Lesefehler ab

## [0.8.1] - 2026-11-05

### Behoben
- TrueHD und DTS-HD brechen die Wiedergabe nicht mehr nach kurzer Zeit mit einem Fehler ab

## [0.8.0] - 2026-11-04

### Neu
- Fotos & Videos: Bibliotheken mit Heimvideos und Fotos bekommen einen eigenen Eintrag in der Navigationsleiste und lassen sich nach Ordnern durchblättern
- Fotos und Videos öffnen sich im Vollbild, mit Diashow auf OK
- Ordner zeigen ihre Bilder als Karten oder als Galerie, gefiltert und nach Datum oder Name sortiert
- Musikvideos sind ein Reiter in Musik, alle auf einmal oder nach Ordnern
- Musikvideos laufen im Musik-Player, auch gemischt mit Songs in der Warteschlange und in Wiedergabelisten
- Musikvideos und ihre Ordner haben die Optionen eines Albums
- Alben, Künstler und Wiedergabelisten haben einen Knopf für ähnliche Musik
- Der Musik-Player hat ein Vollbild mit großem Songtext auf dem unscharfen Cover
- TrueHD und DTS-HD gehen als vollständiger verlustfreier Bitstream an den Receiver
- Unter Einstellungen › Wiedergabe schrumpft die Karte „Nächste Folge“ auf Wunsch auf ihren Countdown

### Verbessert
- Schlägt die Wiedergabe fehl, nennt die Meldung den Fehler und bietet das Protokoll als QR-Code an
- Dolby-Vision-Titel mit Enhancement Layer laufen auf mehr Geräten in Dolby Vision. Unter Einstellungen › Wiedergabe laufen sie stattdessen in HDR10
- Die Wiedergabeprüfung erkennt auch Tonformate, die erst nach einem Moment aussetzen
- Zufällig abspielen spielt ein Album, einen Künstler oder eine Wiedergabeliste gleich in zufälliger Reihenfolge
- Ein Server, der nicht antwortet, wird in der Serverauswahl markiert
- Die Navigationsleiste scrollt ihre mittleren Einträge, wenn der Platz knapp wird
- Beim Übernehmen der Einstellungen eines anderen Geräts siehst du die Geräte und wann jedes sie gespeichert hat
- Die Serverauswahl zählt die Profile so, wie „Wer schaut?“ sie zeigt
- Alben bieten nicht mehr an, sie als gehört zu markieren
- Der Player nennt Musikvideos und Aufnahmen nicht mehr „Filme“
- Die Servereinrichtung zeigt keine Schrittzahl mehr
- Detailseiten lassen um ihre Kartenzeilen überall gleich viel Platz

### Behoben
- Eine neue Wiedergabeliste erscheint sofort in Musik
- Die Knöpfe für den vorherigen und nächsten Titel im Musik-Player sprechen nicht mehr von Folgen
- Rechts vom Profil in der Navigationsleiste springt der Fokus nicht mehr zur A–Z-Leiste einer Bibliothek
- Ein unter derselben Adresse neu eingerichteter Server ersetzt seinen alten Eintrag, und seine Profile bleiben angemeldet

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
