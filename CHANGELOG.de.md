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
- Musikvideos sind ein Reiter in Musik statt eines eigenen Eintrags in der Navigationsleiste, als 16:9-Karten sortiert nach Hinzugefügt, Titel, Erscheinungsjahr oder Künstler
- Der Reiter Musikvideos zeigt alle Videos oder blättert durch ihre Ordner. Solange keine Ansicht gewählt ist, beginnt er mit allen Videos, wenn die meisten ihren Künstler nennen
- Musikvideos laufen im Musik-Player, auch gemischt mit Songs in der Warteschlange: aus ihrem Reiter, der auch alle abspielen oder als Zufallsmix starten kann, von der Künstlerseite und aus Wiedergabelisten. Das Bild steht anstelle des Covers, auch im Mini-Player und im Vollbild
- Musikvideos und ihre Ordner haben Optionen wie ein Album: OK halten, die Menü-Taste oder … in der Werkzeugleiste spielt alle ab oder zufällig, spielt sie als Nächstes oder legt sie in die Warteschlange oder eine Wiedergabeliste. Ein Zeichen im Bild der gewählten Karte weist darauf hin. Auf der Seite einer Wiedergabeliste lässt sich ein Video aus ihr entfernen
- Eine Wiedergabeliste führt ihre Musikvideos zwischen ihren Songs in ihrer Reihenfolge auf und spielt sie zusammen ab
- Musikvideos haben keine eigene Seite mehr: Wo sie erscheinen, spielen sie im Musik-Player
- Alben, Künstler und Wiedergabelisten haben neben Zufällig abspielen einen Knopf für ähnliche Musik
- Der Musik-Player hat ein Vollbild. Ein Song zeigt seinen Songtext groß und mittig auf dem unscharfen Cover, darunter Titel und Zeitleiste. Ohne Songtext pulsiert das Cover sanft
- Glacier schickt TrueHD und DTS-HD als vollständigen verlustfreien Bitstream an den Receiver, auch auf Geräten, auf denen Android nur den DTS-Kern weitergibt oder bei TrueHD scheitert. Unter Einstellungen › Audio lässt sich das Android überlassen, oder der Server wandelt in E-AC-3 um, was nicht als Bitstream rausgeht. Das erhält Surround, wo das Gerät PCM nur in Stereo ausgibt

### Verbessert
- Zufällig abspielen auf einem Album, Künstler oder einer Wiedergabeliste spielt es gleich in zufälliger Reihenfolge, wie in Jellyfins Web-Oberfläche, statt die zufällige Reihenfolge einzuschalten
- Alben bieten nicht mehr an, sie als gehört zu markieren
- Die Navigationsleiste lässt oben rechts Platz für Hinweise. Brauchen ihre Einträge mehr Platz, scrollen die zwischen Suche und Einstellungen, und Suche, Einstellungen und Profil bleiben immer sichtbar
- Der Player nennt Musikvideos und Aufnahmen nicht mehr „Filme“
- Antwortet der Server nicht, öffnet Glacier die Serverauswahl, markiert den Server als nicht erreichbar und entfernt die Markierung, sobald er wieder antwortet
- Die Servereinrichtung zeigt keine Schrittzahl mehr
- Die Frage, ob die Einstellungen eines anderen Geräts übernommen werden sollen, öffnet sich im Vollbild, mit den Geräten und dem Tag, an dem jedes seine Einstellungen gespeichert hat
- Die Serverauswahl zählt die Profile eines Servers so, wie „Wer schaut?“ sie zeigt, auch die nur auf diesem Gerät eingerichteten
- Detailseiten lassen über und unter ihren Kartenzeilen überall gleich viel Platz
- Dolby-Vision-Titel mit Enhancement Layer laufen auf Geräten, deren Decoder ihr Profil nicht kennt, in Dolby Vision, ohne den Enhancement Layer. Unter Einstellungen › Wiedergabe laufen sie stattdessen in HDR10
- Die Wiedergabeprüfung erkennt auch Tonformate, die erst nach einem Moment aussetzen

### Behoben
- Eine neue Wiedergabeliste erscheint sofort in Musik
- Die Knöpfe für den vorherigen und nächsten Titel im Musik-Player sprechen nicht mehr von Folgen
- Rechts vom Profil in der Navigationsleiste springt der Fokus nicht mehr hinunter zur A–Z-Leiste einer Bibliothek
- Ein unter derselben Adresse neu eingerichteter Server ersetzt seinen alten Eintrag, statt doppelt in der Liste zu stehen. Profile, deren Anmeldung er noch akzeptiert, behalten Anmeldung, PIN, Jugendschutz und Einstellungen

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
