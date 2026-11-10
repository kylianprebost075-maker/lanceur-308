package fr.p308.lanceur.backup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.maintenance.DueLevel
import fr.p308.lanceur.maintenance.History
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.maintenance.due
import fr.p308.lanceur.maintenance.fmtKm
import fr.p308.lanceur.maintenance.intervalLabel
import fr.p308.lanceur.maintenance.label
import fr.p308.lanceur.vehicle.CarProfile
import fr.p308.lanceur.vehicle.Vehicle
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Carnet d'entretien en PDF (v0.26) : prochaines échéances puis toutes les interventions notées, sur des pages A4.
 * Écrit sur la clé avec les tableaux Excel (dossier « export ») à chaque sauvegarde, et à la demande depuis
 * Entretien → Historique. Utile pour une revente ou pour le garage.
 */
object MaintenancePdf {
    const val NAME = "carnet-entretien.pdf"
    private const val W = 595 // A4 en points
    private const val H = 842
    private const val LEFT = 42f
    private const val RIGHT = W - 42f
    private const val BOTTOM = H - 54f
    private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)
    private val SHORT = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

    /**
     * Coupe un texte en lignes qui tiennent dans [max] (largeur donnée par [measure]). Un mot plus long que la ligne
     * est coupé. Calcul pur, vérifié par MaintenancePdfTest.
     */
    fun wrap(text: String, max: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        text.split('\n').forEach { paragraph ->
            var line = ""
            paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                var w = word
                while (measure(w) > max && w.length > 1) { // mot plus long que la ligne
                    var n = w.length - 1
                    while (n > 1 && measure(w.take(n)) > max) n--
                    if (line.isNotEmpty()) { out += line; line = "" }
                    out += w.take(n); w = w.drop(n)
                }
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (measure(candidate) <= max) line = candidate else { out += line; line = w }
            }
            if (line.isNotEmpty()) out += line
        }
        return out
    }

    /** Écrit page après page ; ouvre une nouvelle page quand il n'y a plus de place. */
    private class Writer(val doc: PdfDocument) {
        var pageNo = 0
        var page: PdfDocument.Page? = null
        lateinit var c: Canvas
        var y = 0f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()).also { c = it.canvas }
            y = 56f
        }

        fun finishPage() {
            val pg = page ?: return
            style(9f, false, 0xFF777777.toInt())
            p.textAlign = Paint.Align.CENTER
            c.drawText("Carnet d'entretien · ${CarProfile.NAME} · page $pageNo · fait avec Lanceur 308", W / 2f, H - 28f, p)
            p.textAlign = Paint.Align.LEFT
            doc.finishPage(pg)
            page = null
        }

        fun style(size: Float, bold: Boolean, color: Int = 0xFF16181D.toInt()) {
            p.textSize = size
            p.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            p.color = color
        }

        /** Réserve [h] points ; passe à la page suivante s'ils ne tiennent pas. */
        fun need(h: Float) { if (page == null || y + h > BOTTOM) newPage() }

        /** Texte sur plusieurs lignes si besoin ; [right] est écrit à droite de la première ligne. */
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = 0xFF16181D.toInt(), right: String? = null, indent: Float = 0f) {
            style(size, bold, color)
            val rightW = right?.let { p.measureText(it) + 12f } ?: 0f
            val lines = wrap(s, RIGHT - LEFT - indent - rightW) { p.measureText(it) }
            lines.forEachIndexed { i, line ->
                need(size * 1.35f)
                style(size, bold, color)
                y += size * 1.35f
                c.drawText(line, LEFT + indent, y, p)
                if (i == 0 && right != null) { p.textAlign = Paint.Align.RIGHT; c.drawText(right, RIGHT, y, p); p.textAlign = Paint.Align.LEFT }
            }
        }

        fun gap(h: Float) { y += h }

        fun rule() {
            need(10f)
            y += 6f
            p.color = 0xFFD9DCE3.toInt(); p.strokeWidth = 1f
            c.drawLine(LEFT, y, RIGHT, y, p)
            y += 4f
        }

        fun section(title: String) {
            need(60f)
            gap(14f)
            text(title, 14f, bold = true, color = 0xFF2457C5.toInt())
            rule()
        }
    }

    /** Le PDF complet, en mémoire. */
    fun bytes(today: LocalDate = LocalDate.now()): ByteArray {
        val doc = PdfDocument()
        val w = Writer(doc)
        val km = Vehicle.state.value.odometerKm
        w.newPage()
        w.text("Carnet d'entretien", 22f, bold = true)
        w.text("${CarProfile.NAME} ${CarProfile.ENGINE_LABEL}" + (UiPrefs.plate.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), 13f)
        w.text("Édité le ${today.format(DATE)}" + (km?.let { " · ${fmtKm(it)} km au compteur" } ?: ""), 10.5f, color = 0xFF5B6170.toInt())

        val items = Maintenance.items.value
        w.section("Prochaines échéances")
        if (items.isEmpty()) w.text("Aucune échéance enregistrée.", 10.5f, color = 0xFF5B6170.toInt())
        items.forEach { item ->
            val due = item.due(km, today)
            val color = when (due.level) {
                DueLevel.LATE -> 0xFFB3261E.toInt()
                DueLevel.SOON -> 0xFFB06A00.toInt()
                else -> 0xFF16181D.toInt()
            }
            w.need(34f)
            w.text(item.name, 11f, bold = true, right = due.label())
            val last = listOfNotNull(item.lastDate?.format(SHORT), item.lastKm?.let { "${fmtKm(it)} km" }).joinToString(" à ")
            w.text(listOfNotNull(item.intervalLabel().takeIf { it.isNotEmpty() }, last.takeIf { it.isNotEmpty() }?.let { "dernière fois : $it" })
                .joinToString(" · ").ifEmpty { "à renseigner" }, 9.5f, color = if (due.level == DueLevel.LATE || due.level == DueLevel.SOON) color else 0xFF5B6170.toInt())
            w.gap(3f)
        }

        val history = History.entries.value
        val total = history.mapNotNull { it.priceEur }.sum()
        w.section("Interventions réalisées (${history.size})" + if (total > 0f) " · %.0f € de factures".format(Locale.FRANCE, total) else "")
        if (history.isEmpty()) w.text("Aucune intervention notée.", 10.5f, color = 0xFF5B6170.toInt())
        history.forEach { h ->
            val whenText = h.date?.format(SHORT) ?: h.dateNote.ifEmpty { "date inconnue" }
            w.need(34f)
            w.text(listOfNotNull(whenText, h.km?.let { "${fmtKm(it)} km" }, h.title).joinToString(" · "), 11f, bold = true,
                right = h.priceEur?.let { "%.2f €".format(Locale.FRANCE, it) })
            if (h.details.isNotEmpty()) w.text(h.details, 9.5f, indent = 10f)
            if (h.place.isNotEmpty()) w.text(h.place, 9.5f, color = 0xFF5B6170.toInt(), indent = 10f)
            w.gap(4f)
        }
        w.finishPage()
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    /** Écrit le carnet dans le dossier « export » de chaque clé (sinon de la mémoire interne) ; renvoie le message à afficher. */
    fun write(ctx: Context): String {
        val data = runCatching { bytes() }.getOrElse { return "PDF impossible : ${it.message}" }
        val vols = UsbStorage.volumes(ctx)
        val keys = vols.filter { it.usb }
        val done = keys.ifEmpty { vols }.count { v ->
            val dir = File(v.dir, "export").apply { mkdirs() }
            UsbStorage.writeSafe(File(dir, NAME), data)
        }
        return when {
            done == 0 -> "PDF non écrit. ${UsbStorage.missingReason(ctx)}"
            keys.isEmpty() -> "PDF écrit dans la mémoire interne (export/$NAME). ${UsbStorage.missingReason(ctx)}"
            else -> "PDF écrit sur la clé : Android/data/fr.p308.lanceur/files/export/$NAME"
        }
    }
}
