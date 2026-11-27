# Changelog (Deutsch)

The German release notes, shown in the app when its language is German.
Every version section mirrors the one in [CHANGELOG.md](CHANGELOG.md), with
these subsections in this order: `### Neu`, `### Verbessert`, `### Behoben`,
`### Bekannte Probleme`. Versions before 0.6.3 exist only in English.

## [Unreleased]

### Neu
- Eine Reihe Aufnahmen auf der Startseite und Aufnahmen als wählbare Quelle für das Spotlight
- Eine Reihe Aufnahmen auf der Favoritenseite
- Foto- und Videodetails in der Fotoansicht. Mit Unten oder der Info-Taste zeigt der erste Reiter, wann und wo es entstand und in welchem Ordner es liegt, der Reiter Technik die Kamera, die Belichtung und die Datei. Ein Video zeigt dieselben Reiter Technik und Server wie der Player. OK halten oder Menü drücken öffnet die Optionen, mit denen sich ein Foto oder Video nach einer Rückfrage vom Server löschen lässt, wenn der Server es erlaubt. Das geht auch, indem du in der Ordneransicht OK auf einem Foto oder Video hältst (#43). Eine Einstellung füllt die schwarzen Ränder um Fotos und Hochkant-Videos mit einer unscharfen Kopie
- Detailseiten von Filmen und Serien mit mehreren Hintergrundbildern zeigen sie nacheinander

### Verbessert
- Die Zeile über einem Titel ist jetzt ein Pfad. Seine Ebenen öffnen die Bibliothek, die Serie, die Staffel oder einen übergeordneten Ordner
- Specials, die eine Staffel zwischen ihren Folgen zeigt, tragen ein Abzeichen „Special“
- Abzeichen auf Folgenbildern und Plakaten haben einen dunkleren Hintergrund und bleiben auf hellen Bildern lesbar
- Die Folgendetails nennen ein Special „Specials“ statt Staffel 0
- Die Tonspur- und Untertitelwahl während der Wiedergabe zeigt unter dem Titel kein „Gilt ab sofort“ mehr
- Nach Jahr sortiert folgen Titel desselben Jahres ihrem Erscheinungsdatum statt dem Alphabet
- Wer nach oben oder unten in eine Reihe wechselt, landet jetzt zuerst auf ihrer ersten Karte. Danach merkt sich die Reihe die Karte, auf der du sie verlassen hast, auch nach der Rückkehr von einer Detailseite
- A bis Z ignoriert Artikel wie „The“, „Der“ oder „La“ in der Sprache des jeweiligen Titels. Die Sprache kommt von deinem Jellyfin-Server. Unter Einstellungen › Darstellung › Artikel ignorieren schaltest du es aus
- Beim Blättern erscheinen Name und Datum des nächsten Fotos sofort, dazu eine unscharfe Vorschau mit Ladeanzeige, bis das Foto scharf ist. Die nächsten Fotos laden vorab, so wartet das Blättern seltener. Ein Foto, das nicht lädt, lässt sich mit OK erneut versuchen (#43)
- Die Fotoansicht zeigt ihre Tasten beim Öffnen als Tastenkappen in der unteren Ecke, mit Oben erscheinen sie wieder (#43)
- Videos in einem Fotoordner tragen ein Wiedergabezeichen, und Clips unter einer Minute zeigen ihre Länge in Sekunden (#43)
- Nach einem Wechsel auf einen kleineren Stream kehrt die Wiedergabe viel früher zur vollen Qualität zurück: nach etwa 40 Sekunden statt nach mehreren Minuten, mit weniger Wartezeit vor dem Wechsel
- Das Infofenster zeigt, wann die nächste Prüfung der Leitung läuft, solange ein kleinerer Stream spielt
- Heimvideos ohne Bild auf dem Server zeigen im Raster, in der Galerie und in der Fotoansicht ein Standbild aus dem Video. Hochkant-Videos und hochkant gefilmte Handyvideos stehen dabei richtig herum

### Behoben
- Fotos und Videos zeigen die Aufnahmezeit in der Zeitzone des Fernsehers. Vorher lag sie um den Abstand zu UTC daneben (#43)
- Die Specials-Staffel zeigt ihre Folgen in der richtigen Reihenfolge statt nach Titel
- Gedrehte Heimvideos stehen jetzt auch auf Fernsehern aufrecht, die die Drehung ignorierten (#43)
- Live-TV-Aufnahmen erscheinen nicht mehr unter Filmen und Serien. Sie bleiben unter Live-TV > Aufnahmen und die Suche findet sie weiterhin
- YouTube-Trailer bleiben nicht mehr auf dem Ladebildschirm hängen und schlagen nicht mehr fehl, obwohl sie schon laufen
- Die Wiedergabe wechselt wegen eines einzelnen langsamen Verbindungstests nicht mehr auf einen kleineren Stream, solange sie mithält. Der Test teilt sich die Leitung mit dem Video, daher wird er wiederholt und zuerst mit der Geschwindigkeit des Players abgeglichen
- Eine Folge startet nicht mehr mit Untertiteln (oder Ton), die der Server für eine frühere Folge gewählt hat, etwa japanischen Untertiteln. Die nächste Folge übernimmt nur Spuren, die du selbst gewählt hast
- Heimvideos zeigen kurz vor dem Ende keinen Countdown „Nächste Folge“ mehr. Das nächste Video des Ordners startet weiterhin, wenn eines endet

## [0.10.1] - 2026-11-18

### Verbessert
- Die Wiedergabe funktioniert auch bei langsamen Verbindungen von etwa 1 Mbit/s und weniger: Der Geschwindigkeitstest misst sie richtig, kleine Bilder passen sich der Verbindung an, und auch der Ton ist klein

### Behoben
- Detailseite und Player stürzen bei einer Spur mit unbekannter Sprachangabe nicht mehr ab
- Ein beschädigter DTS-Stream bricht die Wiedergabe mit Audio-Passthrough nicht mehr ab
- Bei pausierter Wiedergabe startet wieder der Bildschirmschoner des Fernsehers, statt dass der Bildschirm nur dunkler wird

## [0.10.0] - 2026-11-13

### Neu
- Eine einzelne Staffel lässt sich als gesehen oder ungesehen markieren (OK auf der Staffel gedrückt halten), gesehene Staffeln zeigen einen Haken

### Verbessert
- Optionsmenüs öffnen sich als Seitenleiste. Die Seite bleibt sichtbar und jede Option sagt, was sie tut
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
- Das Infofeld eines Films oder einer Folge zeigt die Besetzung mit Fotos und Rollen statt Kapitel und nächster Folge, die der Player schon anzeigt
- Das Infofeld eines Songs stellt den Song in den Mittelpunkt: Album, Jahr, Titelnummer und Genre, wie oft und wann er zuletzt gehört wurde, dazu der Künstler mit Foto und Biografie. „Als Nächstes“ und die Warteschlange, die der Player schon zeigt, entfallen. Ein Musikvideo zeigt sein Standbild, seine Länge und wie oft es gesehen wurde
- Das Live-TV-Infofeld zeigt das Programm des Senders als Linie mit den kommenden Sendungen statt der Karten für Jetzt und Danach
- Zurück auf den Reitern einer Bibliothek, von Live TV oder Ordnern führt direkt zur Startseite. Dort bleibt der Fokus in der Navigation, statt nach dem Laden in die Seite zu springen
- Zurück in den Reihen der Startseite scrollt wieder nach oben
- Bibliotheken und Live TV behalten ihre Filter (Herz, Ungesehen) und die Sortierung, auch nach einem Neustart. Bis zum Profilwechsel kehren sie außerdem zum zuletzt genutzten Reiter oder zur Ansicht, zum Ordner und zum Titel zurück

### Behoben
- „Untertitel aus“ auf der Detailseite gilt auch für die nächste Folge
- „Als Nächstes“ wartet, solange nach dem Abspann noch eine Szene folgt
- Wird eine Serie als gesehen oder ungesehen markiert, ziehen Folgen und Staffeln sofort mit
- Während etwas lädt oder verbindet, springt der Fokus mit der Fernbedienung nicht mehr auf Schaltflächen hinter dem Ladebildschirm, und ein zweites OK meldet nicht doppelt an
- Der Fokus springt nicht mehr weg, wenn man schon weiternavigiert hat, während eine Seite fertig lädt
- Zurück im Musik-Bildschirmschoner beendet nur den Bildschirmschoner, die Seite dahinter bleibt, wie sie war

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
