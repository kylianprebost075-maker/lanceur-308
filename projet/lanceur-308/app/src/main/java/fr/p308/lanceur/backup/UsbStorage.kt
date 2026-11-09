package fr.p308.lanceur.backup

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * Clé USB (ou carte SD) et écriture sûre dessus (v0.17).
 *
 * Détection : sur l'autoradio FYT, une clé branchée n'est pas toujours déclarée « amovible » par Android, et
 * l'export répondait « aucune clé USB » alors qu'elle était branchée (relevé du 09/11). Tout dossier de l'appli
 * hors mémoire interne et monté est maintenant pris comme une clé.
 *
 * Écriture : le 09/11 à 20:08, une copie coupée (clé retirée ou contact coupé) a laissé des fichiers de 0 octet,
 * y compris la sauvegarde. Chaque fichier est maintenant écrit à côté (« .tmp »), forcé sur la clé (sync), puis
 * renommé : en cas de coupure, l'ancien fichier reste entier.
 */
object UsbStorage {
    /** Un dossier de l'appli : sur une clé (true) ou dans la mémoire interne (false). */
    data class Volume(val dir: File, val usb: Boolean)

    /** Tous les dossiers de l'appli disponibles en écriture : mémoire interne puis clés. */
    fun volumes(ctx: Context): List<Volume> {
        val primary = ctx.getExternalFilesDir(null)?.absolutePath
        return ctx.getExternalFilesDirs(null).filterNotNull().filter { state(it) == Environment.MEDIA_MOUNTED }.map { dir ->
            val removable = runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(false)
            val usb = removable || (dir.absolutePath != primary && !dir.absolutePath.contains("/emulated/"))
            Volume(dir, usb)
        }
    }

    /** Dossiers de l'appli sur les clés branchées et utilisables. */
    fun keys(ctx: Context): List<File> = volumes(ctx).filter { it.usb }.map { it.dir }

    private fun state(dir: File): String = runCatching { Environment.getExternalStorageState(dir) }.getOrDefault(Environment.MEDIA_UNKNOWN)

    /** Phrase à afficher quand aucune clé n'est utilisable : dit pourquoi si Android le sait. */
    fun missingReason(ctx: Context): String {
        val others = ctx.getExternalFilesDirs(null).drop(1)
        val states = others.mapNotNull { it?.let(::state) }
        return when {
            Environment.MEDIA_MOUNTED_READ_ONLY in states ->
                "Clé USB en lecture seule : branche-la sur le PC (Windows propose de la réparer), puis réessaie"
            others.any { it == null } || states.any { it != Environment.MEDIA_MOUNTED } ->
                "Clé USB vue mais pas prête : attends 10 s, ou débranche-la et rebranche-la, puis réessaie"
            else -> "Aucune clé USB détectée : débranche-la et rebranche-la, attends 10 s, puis réessaie"
        }
    }

    /** Écrit [bytes] dans [dst] sans jamais laisser un fichier vide ou coupé. Vrai si réussi. */
    fun writeSafe(dst: File, bytes: ByteArray): Boolean = runCatching {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (tmp.length() != bytes.size.toLong()) {
            tmp.delete()
            return false
        }
        // Sur une clé (FAT), renommer par-dessus un fichier existant échoue : l'ancien est retiré juste avant.
        if (dst.exists() && !dst.delete()) {
            tmp.delete()
            return false
        }
        tmp.renameTo(dst) || run { tmp.copyTo(dst, overwrite = true); tmp.delete(); dst.length() == bytes.size.toLong() }
    }.getOrDefault(false)

    fun writeSafe(dst: File, text: String): Boolean = writeSafe(dst, text.toByteArray(Charsets.UTF_8))

    /** Copie [src] vers [dst] de la même façon sûre. */
    fun copySafe(src: File, dst: File): Boolean = runCatching { writeSafe(dst, src.readBytes()) }.getOrDefault(false)

    /** Supprime les « .tmp » laissés par une copie coupée. */
    fun cleanTmp(dir: File) {
        dir.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".tmp") }.forEach { runCatching { it.delete() } }
    }
}
