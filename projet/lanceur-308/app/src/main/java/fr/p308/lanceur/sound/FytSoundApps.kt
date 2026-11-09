package fr.p308.lanceur.sound

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import java.io.File

/**
 * Pour piloter un jour le DSP de l'autoradio directement : il faut d'abord connaître les commandes de l'appli « son »
 * de FYT. Ici on ne fait que COPIER sur la clé USB les fichiers des applis système qui touchent au son (et le service
 * com.syu.ms), pour les étudier sur le PC. Rien n'est modifié sur l'autoradio.
 */
object FytSoundApps {
    /** Début d'un morceau du nom de paquet (« com.syu.eq », « com.xx.soundsetting »…), pas n'importe où dedans. */
    private val WORDS = listOf("eq", "sound", "audio", "dsp", "amp", "effect")

    data class AppFile(val pkg: String, val label: String, val files: List<File>)

    fun list(ctx: Context): List<AppFile> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).asSequence()
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .filter { a ->
                val p = a.packageName.lowercase()
                p.startsWith("com.syu.") || p.startsWith("com.fyt.") || WORDS.any { w -> p.split('.').any { it.startsWith(w) } }
            }
            .filter { !it.packageName.startsWith("com.android.") || it.packageName.contains("audio") }
            .map { a ->
                val files = (listOfNotNull(a.sourceDir) + (a.splitSourceDirs?.toList() ?: emptyList())).map(::File)
                AppFile(a.packageName, runCatching { pm.getApplicationLabel(a).toString() }.getOrDefault(a.packageName), files)
            }
            .sortedBy { it.pkg }
            .toList()
    }

    /** Copie les fichiers sur la clé (dossier apk-fyt/). Renvoie un message pour l'écran. */
    fun copyToUsb(ctx: Context): String {
        val usb = ctx.getExternalFilesDirs(null).filterNotNull()
            .filter { runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }
        if (usb.isEmpty()) return "Aucune clé USB détectée"
        val apps = list(ctx)
        var copied = 0
        var refused = 0
        usb.forEach { dir ->
            val out = File(dir, "apk-fyt").apply { mkdirs() }
            apps.forEach { app ->
                app.files.forEachIndexed { i, f ->
                    val name = app.pkg + (if (i == 0) "" else "-$i") + ".apk"
                    if (runCatching { f.copyTo(File(out, name), overwrite = true) }.isSuccess) copied++ else refused++
                }
            }
        }
        return "$copied fichier(s) copié(s) sur la clé (Android/data/fr.p308.lanceur/files/apk-fyt/)" +
            (if (refused > 0) ", $refused illisible(s)" else "") + ". Rapporte la clé au PC."
    }
}
