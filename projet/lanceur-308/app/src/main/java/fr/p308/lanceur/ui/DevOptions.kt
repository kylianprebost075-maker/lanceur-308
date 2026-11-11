package fr.p308.lanceur.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import fr.p308.lanceur.vehicle.ObdLog

/**
 * Options pour les développeurs d'Android (v0.21). Les réglages de l'autoradio FYT ne montrent pas cette page
 * (essai du 10/11/2026 : « Paramètres système » ne contient que Date et heure, Accessible, À propos), mais elle
 * existe toujours dans Android : le Lanceur l'ouvre directement. C'est là que se trouvent « Débogage USB » et,
 * selon l'autoradio, « Configuration USB ». Le Lanceur ne change aucun réglage lui-même : il ouvre la page et
 * affiche l'état, c'est tout.
 */
object DevOptions {
    private fun global(ctx: Context, key: String): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, key, 0) }.getOrDefault(0)

    /** Phrase d'état pour les Réglages. */
    fun status(ctx: Context): String {
        val dev = global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        val adb = global(ctx, Settings.Global.ADB_ENABLED) == 1
        return when {
            adb -> "Débogage USB : activé"
            dev -> "Options activées, débogage USB : coupé"
            else -> "Options pas activées, débogage USB : coupé"
        }
    }

    /** Pages essayées dans l'ordre : la page développeur, puis ses anciens noms dans l'appli Réglages d'Android. */
    private fun devIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity")),
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DevelopmentSettings")),
    )

    private fun start(ctx: Context, intent: Intent): Boolean =
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    /** Ouvre la page développeur. Renvoie la phrase à afficher (réussite ou ce qu'il faut faire à la place). */
    fun open(ctx: Context): String {
        // Tant que les options ne sont pas activées, Android 8 remplace la page par un simple message et la referme
        // (vu dans l'émulateur le 10/11/2026) : on ouvre alors « À propos », où se trouve le « Numéro de build ».
        // Android dit lui-même vers quelle page il enverrait : « …DisabledActivity » = options pas vraiment activées.
        val target = runCatching {
            ctx.packageManager.resolveActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0)?.activityInfo?.name
        }.getOrNull()
        if (global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) != 1 || target?.contains("Disabled") == true) {
            val about = listOf(
                Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).setPackage("com.android.settings"),
                Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            ).any { start(ctx, it) }
            ObdLog.log("DEV", "options pour les développeurs pas activées ; page À propos " + if (about) "ouverte" else "introuvable")
            return if (about) "Pas encore activées : sur la page ouverte, appuie 7 fois sur « Numéro de build » (code 3368 si demandé), puis reviens ici et rappuie sur Ouvrir."
            else "Pas encore activées, et la page « À propos » d'Android ne s'ouvre pas. Essaie « Réglages Android »."
        }
        val ok = devIntents().any { start(ctx, it) }
        ObdLog.log("DEV", if (ok) "page des options pour les développeurs ouverte" else "page des options pour les développeurs introuvable")
        return if (ok) "Page ouverte : active « Débogage USB » et regarde s'il y a une ligne « Configuration USB »."
        else "Cet autoradio n'a pas cette page. Essaie « Réglages Android »."
    }

    // --- ADB par le Wi-Fi (v0.22) ---
    // La fiche technique du 10/11/2026 montre : débogage activé, service adbd en marche, mais aucun port réseau
    // (service.adb.tcp.port vide) et une prise USB qui reste « hôte ». Elle montre aussi SELinux en mode « Permissive » :
    // sur cet autoradio, une appli ordinaire a donc une chance de pouvoir régler ce port elle-même. On essaie, on
    // relit, et on dit honnêtement si l'autoradio a accepté. Le réglage disparaît au redémarrage de l'autoradio.

    private const val ADB_PORT = "5555"

    /**
     * v0.32 : redémarre l'autoradio (Applis → Redémarrer). Une appli ordinaire n'a pas le droit REBOOT ; sur cet
     * autoradio, SELinux est « Permissive » et `setprop` passe déjà pour ADB par le Wi-Fi, donc on demande le
     * redémarrage par la propriété `sys.powerctl`, puis par `svc power reboot` et `reboot`. Si rien n'a marché après
     * 10 s, renvoie la phrase à afficher (sinon l'autoradio redémarre avant). À appeler hors du fil de l'écran.
     */
    fun reboot(): String {
        sh("setprop sys.powerctl reboot")
        Thread.sleep(4000)
        sh("svc power reboot")
        Thread.sleep(3000)
        sh("reboot")
        Thread.sleep(3000)
        return "L'autoradio refuse le redémarrage par une appli. Maintiens le bouton marche de l'autoradio, ou coupe et remets le contact."
    }

    private fun sh(cmd: String): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
        out.trim()
    }.getOrDefault("")

    /** Port ADB réseau en service, ou null. */
    fun wifiAdbPort(): String? = sh("getprop service.adb.tcp.port").takeIf { it.isNotEmpty() && it != "-1" && it != "0" }

    /** Adresse de l'autoradio sur le Wi-Fi, ou null. */
    fun wifiAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address }?.hostAddress
    }.getOrNull() ?: sh("getprop dhcp.wlan0.ipaddress").takeIf { it.isNotEmpty() }

    fun wifiAdbStatus(): String {
        val port = wifiAdbPort() ?: return "Coupé"
        val ip = wifiAddress()
        return if (ip != null) "Activé : sur le PC, adb connect $ip:$port" else "Activé (port $port), mais l'autoradio n'est pas sur un Wi-Fi"
    }

    /** Essaie d'ouvrir (ou de fermer) ADB par le Wi-Fi. À appeler hors du fil de l'écran. Renvoie la phrase à afficher. */
    fun setWifiAdb(on: Boolean): String {
        val before = sh("getprop service.adb.tcp.port")
        sh("setprop service.adb.tcp.port ${if (on) ADB_PORT else "-1"}")
        val after = sh("getprop service.adb.tcp.port")
        val accepted = after == (if (on) ADB_PORT else "-1")
        if (accepted) {
            sh("setprop ctl.restart adbd")
            Thread.sleep(1500)
        }
        val adbd = sh("getprop init.svc.adbd")
        ObdLog.log("DEV", "ADB Wi-Fi ${if (on) "demandé" else "coupé"} : port « $before » → « $after », adbd « $adbd »")
        return when {
            !accepted -> "Refusé par l'autoradio (le port est resté « ${after.ifEmpty { "vide" } } »). Rien n'a changé."
            !on -> "ADB par le Wi-Fi coupé."
            adbd != "running" -> "Port réglé, mais le service ADB n'a pas redémarré (état « $adbd ») : redémarre l'autoradio et réessaie."
            else -> wifiAdbStatus() + ". Une demande d'autorisation s'affichera ici à la première connexion."
        }
    }

    /** Ouvre les vrais réglages d'Android (pas ceux du fabricant), où se trouve « À propos → Numéro de build ». */
    fun openAndroidSettings(ctx: Context): String {
        val ok = listOf(
            Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings")),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        ).any { start(ctx, it) }
        ObdLog.log("DEV", if (ok) "réglages d'Android ouverts" else "réglages d'Android introuvables")
        return if (ok) "Réglages d'Android ouverts : Système → À propos → 7 appuis sur « Numéro de build » (code 3368 si demandé)."
        else "Les réglages d'Android ne s'ouvrent pas sur cet autoradio."
    }
}
