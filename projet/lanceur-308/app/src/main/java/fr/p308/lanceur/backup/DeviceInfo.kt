package fr.p308.lanceur.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import fr.p308.lanceur.optim.Optim
import fr.p308.lanceur.vehicle.ObdLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fiche technique de l'autoradio (v0.19), écrite toute seule sur la clé USB à chaque lancement :
 * `Android/data/fr.p308.lanceur/files/infos-autoradio.txt`. Elle sert à savoir, depuis le PC, ce que l'autoradio
 * permet (mode développeur ADB, système « userdebug », commande su déjà présente, protection SELinux) et quelles
 * applis sont installées, sans photographier les écrans de réglages.
 *
 * Lecture seule : rien n'est modifié sur l'autoradio. Les numéros de série, adresses réseau et identifiants ne sont
 * pas recopiés. Le fichier reste sur la clé ; il n'est jamais publié.
 */
object DeviceInfo {
    const val FILE_NAME = "infos-autoradio.txt"

    /** Propriétés système jamais recopiées (identifiants de l'appareil ou du réseau). */
    private val SECRET = Regex("serial|mac|imei|imsi|iccid|meid|bdaddr|wifi\\.addr|android_id|\\.sn$|uuid|cpuid", RegexOption.IGNORE_CASE)

    /** Lance une commande de lecture et renvoie sa sortie (coupée à [maxLines] lignes) ; jamais bloquante. */
    private fun sh(cmd: String, timeoutMs: Long = 4000, maxLines: Int = 400): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                var n = 0
                p.inputStream.bufferedReader().forEachLine { if (n++ < maxLines) out.append(it).append('\n') }
            }
        }
        reader.start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroy()
            out.append("(pas de réponse en ${timeoutMs / 1000} s)\n")
        }
        reader.join(500)
        out.toString().trim().ifEmpty { "(rien)" }
    }.getOrElse { "(impossible : ${it.message ?: it.javaClass.simpleName})" }

    private fun file(path: String, maxLines: Int = 60): String = runCatching {
        File(path).useLines { it.take(maxLines).joinToString("\n") }.ifEmpty { "(vide)" }
    }.getOrElse { "(illisible)" }

    private fun setting(ctx: Context, key: String): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull() ?: "(absent)"

    /** Le texte complet de la fiche. */
    fun report(ctx: Context): String = buildString {
        fun title(t: String) { append("\n=== ").append(t).append(" ===\n") }
        fun line(k: String, v: Any?) { append(k).append(" : ").append(v ?: "?").append('\n') }

        append("FICHE TECHNIQUE DE L'AUTORADIO — écrite par le Lanceur 308\n")
        line("Date (horloge de l'autoradio)", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date()))
        line("Version du Lanceur", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull())

        title("EN BREF (ce qui compte pour le mode développeur et le débridage)")
        line("Android réel", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), correctif ${Build.VERSION.SECURITY_PATCH}")
        line("Type de système (user = fermé, userdebug / eng = ouvert)", Build.TYPE)
        line("Signature (test-keys = système de développement)", Build.TAGS)
        line("ro.debuggable (1 = « adb root » possible)", sh("getprop ro.debuggable"))
        line("ro.secure (0 = ADB déjà administrateur)", sh("getprop ro.secure"))
        line("ro.adb.secure (0 = pas de confirmation sur l'écran)", sh("getprop ro.adb.secure"))
        line("Options développeur activées", setting(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
        line("Débogage ADB activé", setting(ctx, Settings.Global.ADB_ENABLED))
        line("ADB par le Wi-Fi (port, vide = non)", sh("getprop service.adb.tcp.port; getprop persist.adb.tcp.port"))
        line("Mode USB", sh("getprop sys.usb.config; getprop persist.sys.usb.config"))
        line("Sources inconnues", setting(ctx, "install_non_market_apps"))
        line("SELinux (Enforcing = protection active)", sh("getenforce") + " / " + file("/sys/fs/selinux/enforce", 1))
        line("Démarrage vérifié", sh("getprop ro.boot.verifiedbootstate; getprop ro.boot.veritymode; getprop ro.boot.flash.locked"))
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/su/bin/su", "/system/sbin/su")
            .filter { File(it).exists() }
        line("Commande su présente", if (su.isEmpty()) "non" else su.joinToString())
        line("which su", sh("which su"))
        if (su.isNotEmpty()) line("su -c id", sh("su -c id", 5000))
        line("Identité de l'appli (id)", sh("id"))
        val m = Optim.mem(ctx)
        line("Mémoire", "${m.availMb} Mo libres sur ${m.totalMb} Mo")

        title("APPAREIL (android.os.Build)")
        line("Fabricant / marque", "${Build.MANUFACTURER} / ${Build.BRAND}")
        line("Modèle / appareil / produit", "${Build.MODEL} / ${Build.DEVICE} / ${Build.PRODUCT}")
        line("Carte / matériel", "${Build.BOARD} / ${Build.HARDWARE}")
        line("Version affichée", Build.DISPLAY)
        line("Identifiant de version", Build.ID)
        line("Empreinte", Build.FINGERPRINT)
        line("Chargeur de démarrage", Build.BOOTLOADER)
        line("Processeurs gérés", Build.SUPPORTED_ABIS.joinToString())
        line("Noyau", file("/proc/version", 1))

        title("PROCESSEUR (/proc/cpuinfo)")
        append(file("/proc/cpuinfo", 40)).append('\n')

        title("MÉMOIRE (/proc/meminfo)")
        append(file("/proc/meminfo", 12)).append('\n')

        title("STOCKAGE (df)")
        append(sh("df")).append('\n')

        title("PARTITIONS MONTÉES (/proc/mounts)")
        append(
            file("/proc/mounts", 200).lines()
                .filter { l -> listOf(" / ", " /system", " /vendor", " /data ", " /cache", " /storage", " /mnt/media_rw").any { it in l } }
                .joinToString("\n").ifEmpty { "(illisible)" },
        ).append('\n')

        title("NOMS DES PARTITIONS (souvent interdit à une appli)")
        append(sh("ls -l /dev/block/by-name 2>&1 || ls -l /dev/block/platform/*/by-name 2>&1", maxLines = 80)).append('\n')

        title("PROPRIÉTÉS SYSTÈME (getprop, sans les identifiants)")
        append(
            sh("getprop", 6000, 2000).lines()
                .filter { l -> !SECRET.containsMatchIn(l.substringBefore("]:")) }
                .joinToString("\n"),
        ).append('\n')

        title("APPLIS INSTALLÉES (S = système, U = ajoutée ; « désactivée » si elle l'est)")
        val pm = ctx.packageManager
        runCatching {
            pm.getInstalledApplications(0).sortedBy { it.packageName }.forEach { a ->
                val kind = if (a.flags and ApplicationInfo.FLAG_SYSTEM != 0) "S" else "U"
                val persistent = if (a.flags and ApplicationInfo.FLAG_PERSISTENT != 0) " permanente" else ""
                val off = if (!a.enabled) " désactivée" else ""
                val label = runCatching { pm.getApplicationLabel(a).toString() }.getOrDefault("")
                append(kind).append(' ').append(a.packageName).append(" — ").append(label).append(persistent).append(off).append('\n')
            }
        }.onFailure { append("(liste illisible)\n") }
    }

    /** Écrit la fiche sur chaque clé branchée (et dans la mémoire interne). Renvoie le nombre de clés servies. */
    fun write(ctx: Context): Int {
        val volumes = UsbStorage.volumes(ctx)
        if (volumes.isEmpty()) return 0
        val text = runCatching { report(ctx) }.getOrElse { "Fiche impossible à établir : ${it.message}" }
        var keys = 0
        volumes.forEach { v -> if (UsbStorage.writeSafe(File(v.dir, FILE_NAME), text) && v.usb) keys++ }
        ObdLog.log("INFOS", "fiche technique de l'autoradio écrite" + if (keys > 0) " sur la clé USB" else " (mémoire interne, clé absente)")
        return keys
    }
}
