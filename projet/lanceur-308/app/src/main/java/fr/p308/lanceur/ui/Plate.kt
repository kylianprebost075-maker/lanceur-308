package fr.p308.lanceur.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.vehicle.ObdLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Remplace l'image de la plaque dans un modèle 3D .glb, octet par octet, sans toucher au reste du fichier (v0.23).
 *
 * Le modèle embarqué réserve une place fixe à l'image de la plaque (un PNG de 1040 × 220 complété par un bloc de
 * remplissage). La nouvelle image est complétée de la même façon pour faire exactement la même taille : ni la
 * description du modèle ni les autres données ne bougent, donc rien d'autre ne peut casser.
 * Calcul pur (aucun appel Android) : vérifié par GlbPlateTest.
 */
object GlbPlate {
    const val WIDTH = 1040
    const val HEIGHT = 220
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    /** Nom du bloc de remplissage : « privé, facultatif, recopiable » selon la norme PNG, donc ignoré à l'affichage. */
    private val PAD_TYPE = "paDd".toByteArray(Charsets.US_ASCII)

    /** Une image PNG trouvée dans le fichier : où elle commence, sa taille en octets, ses dimensions. */
    data class Slot(val start: Int, val length: Int, val width: Int, val height: Int)

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun isType(b: ByteArray, at: Int, type: String) = (0..3).all { b[at + it] == type[it].code.toByte() }

    /** Lit le PNG qui commence à [start] ; null si ce n'en est pas un entier (jusqu'à son bloc de fin IEND). */
    private fun pngAt(b: ByteArray, start: Int): Slot? {
        if (start + 8 + 25 > b.size || !SIGNATURE.indices.all { b[start + it] == SIGNATURE[it] }) return null
        var p = start + 8
        if (!isType(b, p + 4, "IHDR")) return null
        val w = int(b, p + 8)
        val h = int(b, p + 12)
        while (p + 12 <= b.size) {
            val len = int(b, p)
            if (len < 0 || p + 12L + len > b.size) return null
            val end = isType(b, p + 4, "IEND")
            p += 12 + len
            if (end) return Slot(start, p - start, w, h)
        }
        return null
    }

    /** Toutes les images PNG contenues dans le fichier. */
    fun findPngs(glb: ByteArray): List<Slot> {
        val found = ArrayList<Slot>()
        var i = 0
        while (i <= glb.size - 8) {
            val s = if (glb[i] == SIGNATURE[0]) pngAt(glb, i) else null
            if (s != null) { found += s; i += s.length } else i++
        }
        return found
    }

    /** La place de la plaque : la seule image de 1040 × 220 du modèle (null s'il n'y en a pas, ou plusieurs). */
    fun plateSlot(glb: ByteArray): Slot? = findPngs(glb).filter { it.width == WIDTH && it.height == HEIGHT }.singleOrNull()

    /** Allonge un PNG jusqu'à [size] octets exactement avec un bloc de remplissage ; null s'il est trop gros. */
    fun pad(png: ByteArray, size: Int): ByteArray? {
        val whole = pngAt(png, 0) ?: return null
        if (whole.length != png.size) return null
        val missing = size - png.size
        if (missing == 0) return png
        if (missing < 12) return null
        val out = ByteArray(size)
        val head = png.size - 12 // tout sauf le bloc de fin IEND (12 octets)
        System.arraycopy(png, 0, out, 0, head)
        val dataLen = missing - 12
        putInt(out, head, dataLen)
        System.arraycopy(PAD_TYPE, 0, out, head + 4, 4)
        val crc = CRC32().apply { update(out, head + 4, 4 + dataLen) }
        putInt(out, head + 8 + dataLen, crc.value.toInt())
        System.arraycopy(png, head, out, size - 12, 12)
        return out
    }

    /** Renvoie une copie du modèle avec la nouvelle plaque ; null si le modèle n'a pas de place ou si l'image est trop grosse. */
    fun replace(glb: ByteArray, png: ByteArray): ByteArray? {
        val slot = plateSlot(glb) ?: return null
        val image = pad(png, slot.length) ?: return null
        return glb.copyOf().also { System.arraycopy(image, 0, it, slot.start, slot.length) }
    }
}

/**
 * Plaque d'immatriculation choisie par l'utilisateur (v0.23) : dessinée par l'appli, puis posée sur la voiture 3D.
 * Le texte reste sur l'autoradio (réglages de l'appli) : l'APK ne contient qu'une plaque d'exemple.
 */
object PlateModel {
    private const val ASSET = "models/car.glb"
    private const val PREFIX = "car-plaque-"
    const val EXAMPLE = "AB-123-CD"
    /** true si le modèle à plaque personnalisée n'a pas pu s'afficher : on revient au modèle d'origine. */
    private var failed = false

    /** Garde lettres, chiffres, tirets et espaces ; 10 caractères au plus. */
    fun normalize(text: String) = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == ' ' }.trim().take(10)

    /** Dessin de la plaque française (bandes bleues, étoiles, F). [flipped] : retournée, comme l'attend le modèle 3D. */
    fun bitmap(text: String, flipped: Boolean = false): Bitmap {
        val w = GlbPlate.WIDTH.toFloat()
        val h = GlbPlate.HEIGHT.toFloat()
        val band = 90f
        val bmp = Bitmap.createBitmap(GlbPlate.WIDTH, GlbPlate.HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (flipped) c.scale(1f, -1f, w / 2, h / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFFFFFFFF.toInt())
        p.color = 0xFF0B3FA8.toInt()
        c.drawRect(0f, 0f, band, h, p)
        c.drawRect(w - band, 0f, w, h, p)
        p.color = 0xFF111111.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 6f
        c.drawRect(3f, 3f, w - 3f, h - 3f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFD200.toInt()
        for (i in 0 until 12) {
            val a = i / 12.0 * 2 * Math.PI
            c.drawCircle(band / 2 + 26f * Math.cos(a).toFloat(), 70f + 26f * Math.sin(a).toFloat(), 4.5f, p)
        }
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 64f
        c.drawText("F", band / 2, 190f, p)
        p.color = 0xFF111111.toInt(); p.textSize = 190f
        val room = w - 2 * band - 50f
        val wide = p.measureText(text)
        if (wide > room) p.textSize *= room / wide
        c.drawText(text, w / 2, h / 2 - (p.ascent() + p.descent()) / 2, p)
        return bmp
    }

    @Suppress("DEPRECATION")
    private fun appVersion(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode }.getOrDefault(0)

    private fun files(ctx: Context) = ctx.filesDir.listFiles { f -> f.name.startsWith(PREFIX) }?.toList().orEmpty()

    /** Fabrique le modèle à la plaque [text] dans le dossier privé de l'appli ; renvoie un message d'erreur, ou null. */
    private fun build(ctx: Context, text: String): String? {
        val glb = runCatching { ctx.assets.open(ASSET).use { it.readBytes() } }.getOrNull() ?: return "Modèle 3D introuvable"
        val png = ByteArrayOutputStream().also { bitmap(text, flipped = true).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val out = GlbPlate.replace(glb, png) ?: return "Ce modèle 3D n'a pas de plaque modifiable"
        files(ctx).forEach { it.delete() }
        // Nom différent à chaque fois : c'est ce qui fait recharger la voiture à l'écran.
        val target = File(ctx.filesDir, "$PREFIX${appVersion(ctx)}-${System.currentTimeMillis() / 1000}.glb")
        val tmp = File(ctx.filesDir, "plaque.tmp")
        return runCatching {
            tmp.writeBytes(out)
            if (!tmp.renameTo(target)) error("copie impossible")
            null
        }.getOrElse { tmp.delete(); "Écriture impossible : ${it.message}" }
    }

    /** Enregistre la plaque tapée dans les Réglages et prépare la voiture ; renvoie le message à afficher. */
    fun apply(ctx: Context, text: String): String {
        val t = normalize(text)
        failed = false
        val error = if (t.isEmpty()) { files(ctx).forEach { it.delete() }; null } else build(ctx, t)
        if (error != null) return error
        UiPrefs.updatePlate(t)
        // Le texte de la plaque n'est volontairement pas écrit dans le journal (il part sur la clé USB).
        ObdLog.log("PLAQUE", if (t.isEmpty()) "plaque d'exemple remise" else "plaque personnalisée posée")
        return if (t.isEmpty()) "Plaque d'exemple ($EXAMPLE)" else "Plaque posée sur la voiture"
    }

    /** Adresse du modèle à plaque personnalisée, refait si besoin après une mise à jour de l'appli ; null = modèle d'origine. */
    fun location(ctx: Context): String? {
        val t = UiPrefs.plate
        if (t.isEmpty() || failed) return null
        val current = files(ctx).firstOrNull { it.name.startsWith("$PREFIX${appVersion(ctx)}-") && it.length() > 0 }
        if (current == null && build(ctx, t) != null) return null
        return files(ctx).firstOrNull()?.let { Uri.fromFile(it).toString() }
    }

    fun isPlate(location: String) = PREFIX in location

    /** Le modèle personnalisé n'a pas pu s'afficher : noté au journal, et on repasse au modèle d'origine. */
    fun markFailed() {
        failed = true
        ObdLog.log("PLAQUE", "le modèle à plaque personnalisée ne s'affiche pas : retour au modèle d'origine")
    }
}
