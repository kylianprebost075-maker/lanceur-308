package fr.p308.lanceur.backup

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import fr.p308.lanceur.vehicle.ObdLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal des plantages (v0.20). Sans ADB, un plantage dans la voiture ne laissait aucune trace. Maintenant, si
 * l'appli plante, le détail (où et pourquoi dans le code) est écrit dans `plantages/plantage-<date>.txt` :
 * - tout de suite dans la mémoire de l'appli, et sur la clé si elle est branchée ;
 * - au lancement suivant, recopié sur la clé dès qu'elle est là (par [AutoBackup]), et signalé au journal.
 *
 * Le fichier ne contient que le code de l'appli et l'état de la mémoire : pas de position, pas d'identifiant.
 * Après l'écriture, Android ferme l'appli comme avant (le gestionnaire d'origine est rappelé).
 */
object CrashLog {
    private const val DIR = "plantages"
    private const val KEEP = 20
    private var appCtx: Context? = null

    /** À appeler en tout premier dans [fr.p308.lanceur.App.onCreate]. */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Dossier des plantages dans la mémoire de l'appli (toujours disponible, même sans clé). */
    private fun localDir(ctx: Context): File = File(ctx.filesDir, DIR)

    private fun record(thread: Thread, error: Throwable) {
        val ctx = appCtx ?: return
        val now = Date()
        val name = "plantage-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE).format(now) + ".txt"
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val mem = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            "${mi.availMem / 1_048_576} Mo libres sur ${mi.totalMem / 1_048_576} Mo" + if (mi.lowMemory) " (Android à court)" else ""
        }.getOrDefault("?")
        val rt = Runtime.getRuntime()
        val text = buildString {
            append("PLANTAGE DU LANCEUR 308\n")
            append("Date (horloge de l'autoradio) : ").append(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(now)).append('\n')
            append("Version : ").append(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()).append('\n')
            append("Android : ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Fil : ").append(thread.name).append('\n')
            append("Mémoire de l'autoradio : ").append(mem).append('\n')
            append("Mémoire de l'appli : ").append((rt.totalMemory() - rt.freeMemory()) / 1_048_576).append(" Mo sur ")
                .append(rt.maxMemory() / 1_048_576).append(" Mo permis\n\n")
            append(trace)
        }
        val local = localDir(ctx)
        UsbStorage.writeSafe(File(local, name), text)
        // Sur la clé tout de suite si elle est là (sans attendre : l'appli va être fermée).
        runCatching { UsbStorage.keys(ctx).forEach { UsbStorage.writeSafe(File(File(it, DIR), name), text) } }
        trim(local)
    }

    /** Ne garde que les [KEEP] plantages les plus récents. */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("plantage-") && f.name.endsWith(".txt") } ?: return
        files.sortedByDescending { it.name }.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** Plantages enregistrés, du plus récent au plus ancien. */
    fun list(ctx: Context): List<File> =
        localDir(ctx).listFiles { f -> f.name.startsWith("plantage-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /**
     * Au lancement : signale au journal un plantage pas encore vu, et recopie sur les clés ceux qui n'y sont pas.
     * Renvoie le nombre de fichiers recopiés. Appelé par [AutoBackup] (fil d'arrière-plan).
     */
    fun syncToKeys(ctx: Context): Int {
        val crashes = list(ctx)
        if (crashes.isEmpty()) return 0
        val p = ctx.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val newest = crashes.first().name
        if (p.getString("seen", null) != newest) {
            ObdLog.log("PLANTAGE", "l'appli a planté (${crashes.size} au total, dernier : ${newest.removePrefix("plantage-").removeSuffix(".txt")}) : détail sur la clé, dossier plantages")
            p.edit().putString("seen", newest).apply()
        }
        var copied = 0
        UsbStorage.keys(ctx).forEach { key ->
            val dst = File(key, DIR)
            crashes.forEach { f ->
                val target = File(dst, f.name)
                if (!target.exists() || target.length() != f.length()) {
                    if (UsbStorage.copySafe(f, target)) copied++
                }
            }
        }
        return copied
    }
}
