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
