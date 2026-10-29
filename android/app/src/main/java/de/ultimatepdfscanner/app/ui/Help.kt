package de.ultimatepdfscanner.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import de.ultimatepdfscanner.app.tr

/** What a control does, shown after holding it for [HOLD_MS]. Texts are German, translated when shown. */
data class Explanation(val title: String, val text: String)

private const val HOLD_MS = 1000L

private val LocalExplain = staticCompositionLocalOf<(Explanation) -> Unit> { {} }

/** Shows the explanation window for [explain] anywhere below it (one window for the whole app). */
@Composable
fun ExplainHost(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf<Explanation?>(null) }
    CompositionLocalProvider(LocalExplain provides { shown = it }) { content() }
    shown?.let { e ->
        AlertDialog(onDismissRequest = { shown = null }, icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text(tr(e.title)) }, text = { Text(tr(e.text)) },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("OK") } })
    }
}

/**
 * Holding the control for a second explains it. The press is then swallowed, so the control does
 * not also act; a normal tap (or a swipe) passes through unchanged. Not for controls that have
 * their own long press (page tiles).
 */
fun Modifier.explain(title: String, text: String): Modifier = composed {
    val show = LocalExplain.current
    val haptics = LocalHapticFeedback.current
    pointerInput(title, text) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val endedEarly = withTimeoutOrNull(HOLD_MS) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            } ?: false
            if (endedEarly) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            show(Explanation(title, text))
            // swallow the rest of the press so the control does not act on release
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/** The "?" in a screen's top bar: opens the instructions for that screen. */
@Composable
fun HelpButton(topic: HelpTopic, tint: Color = LocalContentColor.current) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, tr("Hilfe & Anleitung"), tint = tint) }
    if (open) HelpDialog(topic) { open = false }
}

@Composable
private fun HelpDialog(topic: HelpTopic, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) },
        title = { Text(tr(topic.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                topic.sections.forEach { (head, body) ->
                    Column {
                        Text(tr(head), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(tr(body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(tr("Tipp: Einen Knopf eine Sekunde gedrückt halten – dann erscheint, was er macht."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

/** Instructions per screen: title and (heading, text) sections, German, translated when shown. */
enum class HelpTopic(val title: String, val sections: List<Pair<String, String>>) {
    HOME("Startseite", listOf(
        "Scannen" to "Öffnet die Kamera. Alle Fotos einer Aufnahme landen in einem gemeinsamen Dokument.",
        "Fotos importieren" to "Das Galerie-Symbol über „Scannen“ übernimmt vorhandene Fotos; sie werden wie Kamerafotos zugeschnitten und erkannt.",
        "Suchen" to "Findet Scans nach Namen und nach dem erkannten Text, mit Seitenangabe.",
        "Ordner und Kategorien" to "Ordner fassen Scans zusammen. Die Kategorien (Bücher, Belege …) erscheinen automatisch, sobald es Scans dieser Art gibt.",
        "Mehrere Scans" to "Lange auf einen Scan tippen: auswählen, zusammenführen, in einen Ordner verschieben oder löschen.",
        "Papierkorb" to "Gelöschte Scans bleiben 30 Tage im Papierkorb (Menü ⋮) und lassen sich wiederherstellen.",
    )),
    CAMERA("Scannen", listOf(
        "Modi" to "Unten wählen: Dokument (einzelne Blätter), Buch (Doppelseiten werden in zwei Seiten geteilt), Ausweis (Vorder- und Rückseite in Originalgröße auf einem Blatt), Visitenkarte (Kontakt wird erkannt), Bild (farbtreu, ohne Texterkennung), Passfoto (Frontkamera, 35 × 45 mm) und QR-Code.",
        "Bildqualität" to "Oben links: Schnell (5 MP) für große Schrift, Standard (12 MP) für fast alles, Maximal für Kleingedrucktes und Formeln – langsamer.",
        "Auslösung" to "Manuell: mit dem Auslöser. Auto: sobald die Seite ruhig liegt – die Wartezeit (0,2–1,5 s, Standard 0,7 s) stellst du im Scan-Fenster unter dem Schalter ein; im Buch-Modus nach jedem Umblättern. Bluetooth: mit einem gekoppelten Fernauslöser. Die Lautstärketasten lösen immer aus.",
        "Timer und Licht" to "Das Uhr-Symbol macht alle 3, 5 oder 10 Sekunden ein Foto (Start mit dem Auslöser). Der Blitz schaltet die Taschenlampe ein.",
        "Fertig" to "Auf das Vorschaubild rechts unten tippen: Die Seiten werden verarbeitet und die Übersicht öffnet sich.",
        "Gute Fotos" to "Seite flach und gleichmäßig beleuchtet hinlegen, Handy parallel halten. Ein Untergrund, der sich farblich vom Papier abhebt, hilft beim Zuschneiden.",
    )),
    DOCUMENT("Dokument", listOf(
        "Farbe, Graustufen, Schwarz-Weiß" to "Gilt für alle Seiten des Dokuments. Einzelne Seiten lassen sich in der Seitenansicht anders einstellen.",
        "Automatische Verbesserung" to "Entfernt Schatten und Artefakte und macht die Schrift kontrastreicher. Ausschalten zeigt die Seite nur zugeschnitten und entzerrt.",
        "Seiten" to "Antippen öffnet die Seite. Lange drücken: neu aufnehmen, drehen oder löschen. Orange markierte Seiten sollten geprüft werden (z. B. unscharf), rote sind fehlgeschlagen.",
        "Ordnen" to "Reihenfolge per Ziehen ändern, mehrere Seiten drehen, in ein anderes Dokument verschieben oder kopieren, oder als neues Dokument herauslösen.",
        "Exportieren" to "PDF, Word, Bilder, ZIP, Text oder Tabelle – teilen, speichern oder drucken.",
        "Mehr (⋮)" to "Leserichtung von Doppelseiten umkehren, auf dem Gerät übersetzen oder den Scan löschen. Den Namen ändert der Stift oben.",
        "Übersetzen" to "Legt auf dem Gerät eine übersetzte Kopie des Dokuments an: gleiche Seiten und Bilder, der Text steht in der Zielsprache an seiner Stelle. Jeder Absatz wird aus seiner eigenen Sprache übersetzt. Mit „Fachgebiet“ werden Fachbegriffe aus den Fachwörterbüchern genau so übernommen.",
    )),
    PAGE("Seitenansicht", listOf(
        "Blättern und Zoomen" to "Wischen wechselt die Seite, Doppeltippen oder zwei Finger vergrößern.",
        "Filter" to "Farbe, Graustufen oder Schwarz-Weiß nur für diese Seite, mit Vorschau. Darunter die automatische Verbesserung für diese Seite.",
        "Drehen und Zuschnitt" to "Drehen um 90°. Zuschnitt: die Ecken der Seite im Originalfoto neu setzen.",
        "Bearbeiten" to "Mit Stift, Marker oder Text auf die Seite schreiben.",
        "Schwärzen" to "Sensible Daten unkenntlich machen – mit Balken oder verpixelt. Kontonummern, Kartennummern und Gesichter werden vorgeschlagen.",
        "Signieren und Text" to "Signieren setzt deine gespeicherte Unterschrift ein. Text zeigt den erkannten Text zum Korrigieren und Kopieren.",
    )),
    EXPORT("Exportieren", listOf(
        "Formate" to "PDF (durchsuchbar), Word, Bilder (JPG), ZIP, Text, Excel oder CSV; bei Visitenkarten auch als Kontakt.",
        "PDF-Optionen" to "Seitenformat, Auflösung und Kompression bestimmen Qualität und Dateigröße – die Größe wird oben rechts geschätzt. Optional mit Passwort (AES-256).",
        "Teilen oder Speichern" to "Teilen schickt die Datei an eine App (E-Mail, Messenger …). Speichern legt sie am Speicherort ab.",
        "Speicherort" to "„Jedes Mal fragen“ öffnet beim Speichern die Ordnerauswahl. Mit einem festen Ordner landen Exporte ohne Nachfrage dort – auch auf SD-Karte oder in einem Cloud-Ordner.",
        "Drucken" to "Druckt über den Android-Druckdienst oder speichert als PDF.",
    )),
    SETTINGS("Einstellungen", listOf(
        "Aufnahme" to "Bildqualität, Auslösung und Teilung von Doppelseiten für neue Scans. In der Kamera lässt sich das jederzeit umstellen.",
        "Bild" to "Filter und automatische Verbesserung, mit denen neue Dokumente beginnen.",
        "Texterkennung" to "Macht PDFs durchsuchbar. Die Sprachen bestimmen, welche Schriftzeichen erkannt werden.",
        "PDF & Export" to "Speicherort und die Vorgaben für Auflösung, Kompression und Seitenformat.",
        "Sicherheit & Speicher" to "App-Sperre mit Fingerabdruck, Sicherung als ZIP und der Papierkorb.",
        "Was nachgeladen werden kann" to "Alles ist freiwillig und wird nur auf deinen Tipp geladen, danach arbeitet es ohne Internet:\n• Sprachen der Texterkennung: je 1–15 MB (Einstellungen › Sprachen). Deutsch und Englisch sind enthalten.\n• Chinesisch: ein eigenes Erkennungsmodell, ca. 17 MB.\n• Übersetzungsmodelle: ca. 30 MB je Sprache, werden bei der ersten Übersetzung automatisch geladen.\n• Fachwörterbücher: 0,3–9 MB je Sprache, für alle 24 EU-Sprachen zusammen ca. 70 MB (Einstellungen › Fachwörterbücher).\nGeladene Dateien lassen sich jederzeit wieder entfernen.",
    )),
    CROP("Zuschnitt anpassen", listOf(
        "Ecken setzen" to "Die vier Ecken auf die Seitenränder ziehen; die Lupe zeigt die Stelle unter dem Finger vergrößert.",
        "Einzel- oder Doppelseite" to "Unten wählen, ob das Foto eine Seite oder eine aufgeschlagene Doppelseite zeigt. Automatisch entscheidet die App.",
        "Übernehmen" to "Das Häkchen oben verarbeitet die Seite mit dem neuen Zuschnitt.",
    )),
    ANNOTATE("Bearbeiten", listOf(
        "Werkzeuge" to "Stift zum Schreiben, Marker zum Hervorheben, Text für getippte Notizen. Darunter die Farben.",
        "Korrigieren" to "Rückgängig nimmt den letzten Strich zurück.",
        "Sichern" to "Schreibt die Änderungen in die Seite. Ohne Sichern bleibt die Seite unverändert.",
    )),
    REDACT("Schwärzen", listOf(
        "Vorschläge" to "Gefundene Kontonummern, Kartennummern, Steuernummern und Gesichter sind markiert – antippen wählt sie ab oder wieder an.",
        "Eigene Bereiche" to "Mit dem Finger weitere Rechtecke aufziehen.",
        "Balken oder Pixel" to "Schwarzer Balken oder Verpixeln. Der Text darunter wird auch aus der Texterkennung und dem PDF entfernt.",
    )),
    SIGNATURE("Unterschrift", listOf(
        "Zeichnen" to "Einmal mit dem Finger unterschreiben – die Unterschrift wird auf dem Gerät gespeichert.",
        "Platzieren" to "Mit einem Finger verschieben, mit zwei Fingern die Größe ändern, dann einfügen.",
        "Neu zeichnen" to "Das Stift-Symbol oben ersetzt die gespeicherte Unterschrift.",
    )),
    TEXT("Text bearbeiten", listOf(
        "Korrigieren" to "Der erkannte Text der Seite steht unten und lässt sich ändern. Oben das Seitenbild zum Vergleichen.",
        "Wirkung" to "Korrekturen gehen in die Suche, in die Textebene des PDFs und in Word- und Text-Exporte ein.",
    )),
    TRASH("Papierkorb", listOf(
        "Wiederherstellen" to "Holt einen gelöschten Scan zurück.",
        "Endgültig löschen" to "Nach 30 Tagen werden Scans automatisch entfernt. „Leeren“ löscht sofort alles.",
    )),
    STORAGE("Speicher & Sicherung", listOf(
        "Belegung" to "Zeigt, wie viel Platz Scans, Originalfotos, Papierkorb, Zwischenspeicher und Sprachdaten (Texterkennung, Fachwörterbücher) belegen.",
        "Platz schaffen" to "Originalfotos entfernen spart viel Platz; die fertigen Seiten bleiben, nur der Zuschnitt ist danach nicht mehr änderbar.",
        "Sicherung" to "Alle Scans als ZIP-Datei sichern und auf diesem oder einem anderen Gerät wiederherstellen. Nichts wird hochgeladen.",
    )),
    OCR_LANGUAGES("Sprachen der Texterkennung", listOf(
        "Auswahl" to "Die Sprachen der Dokumente wählen. Jede zusätzliche Sprache macht die Erkennung langsamer.",
        "Laden" to "Fehlende Sprachen werden einmalig geladen und arbeiten danach ohne Internet.",
    )),
    GLOSSARIES("Fachwörterbücher", listOf(
        "Wozu" to "Allgemeine Übersetzer kennen viele Fachbegriffe nicht oder übersetzen sie uneinheitlich. Ein Fachwörterbuch setzt den festgelegten Fachbegriff ein, z. B. „Beweislast“ → „burden of proof“.",
        "Quelle" to "IATE, die Terminologiedatenbank der EU-Institutionen, mit Begriffen aus 21 Fachgebieten (Recht, Gesundheit, Technik, Wirtschaft, Umwelt, Verkehr …). Es gibt sie für die 24 Amtssprachen der EU.",
        "Was nötig ist" to "Je ein Wörterbuch für die Ausgangs- und die Zielsprache, z. B. Deutsch und Englisch. Ein Wörterbuch pro Sprache genügt für alle Sprachpaare.",
        "Laden oder importieren" to "Mit dem Pfeil laden (einmalig, danach offline) oder mit dem Ordner-Symbol eine Datei „glossary-xx.tsv.gz“ importieren, etwa vom PC oder einer SD-Karte.",
        "Benutzen" to "Beim Übersetzen oben das Fachgebiet wählen. „Alle Fachgebiete“ nutzt nur Begriffe, die überall gleich übersetzt werden.",
        "Grenzen" to "Fachbegriffe werden in ihrer Grundform eingesetzt; gebeugte Formen („der Mitgliedstaaten“) erkennt das Wörterbuch nicht immer.",
    )),
    SEND_TO_PC("An PC senden", listOf(
        "Verbinden" to "Handy und PC ins selbe WLAN bringen (oder den PC mit dem Hotspot des Handys).",
        "Herunterladen" to "Die angezeigte Adresse am PC im Browser öffnen oder den QR-Code scannen; dort lassen sich alle Scans als PDF oder Text laden.",
        "Sicherheit" to "Funktioniert nur im lokalen Netz und nur, solange dieser Bildschirm offen ist.",
    )),
}
