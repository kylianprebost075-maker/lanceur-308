package fr.p308.lanceur.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import fr.p308.lanceur.PerfPanel
import fr.p308.lanceur.Slot
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.guessPackage

/**
 * Ouverture automatique du panneau quand CarPlay arrive au premier plan tout seul (téléphone branché), pas
 * seulement par le bouton du dock. Android ne dit quelle appli est à l'écran qu'avec l'autorisation
 * « Accès aux données d'utilisation », donnée à la main par l'utilisateur (Réglages du Lanceur → Autoriser).
 * Sans elle, rien ne change : le bouton CarPlay du dock reste le moyen d'afficher le panneau.
 */
object CarPlayWatch {
    private var lastQueryMs = 0L
    private var foreground: String? = null
    /** Vrai si c'est cette surveillance (et non le bouton du dock) qui a affiché le panneau. */
    private var armedByUs = false

    fun hasAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Appli passée au premier plan en dernier (null si inconnue). Lit seulement les événements récents. */
    private fun updateForeground(ctx: Context, now: Long) {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val from = if (lastQueryMs == 0L) now - 60_000L else lastQueryMs - 1_000L
        lastQueryMs = now
        val events = runCatching { usm.queryEvents(from, now) }.getOrNull() ?: return
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) foreground = e.packageName
        }
    }

    /** Appelé toutes les 2 s par le service. */
    fun tick(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!UiPrefs.perfOnCarPlay || !UiPrefs.perfAutoCarPlay || !hasAccess(ctx)) return
        val carplay = UiPrefs.slots[Slot.CARPLAY] ?: guessPackage(ctx, Slot.CARPLAY) ?: return
        updateForeground(ctx, now)
        val fg = foreground ?: return
        when {
            fg == carplay && !PerfPanel.armed.value -> {
                PerfPanel.armed.value = true
                armedByUs = true
            }
            // Une autre appli (pas le Lanceur, qui gère déjà ce cas, ni la barre système) : le panneau s'efface.
            fg != carplay && fg != ctx.packageName && fg != "com.android.systemui" && armedByUs && PerfPanel.armed.value -> {
                PerfPanel.armed.value = false
                armedByUs = false
            }
        }
        if (!PerfPanel.armed.value) armedByUs = false
    }
}
