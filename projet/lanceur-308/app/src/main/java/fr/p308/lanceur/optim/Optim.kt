package fr.p308.lanceur.optim

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.p308.lanceur.AppVisibility
import fr.p308.lanceur.PerfPanel
import fr.p308.lanceur.Slot
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.guessPackage
import fr.p308.lanceur.vehicle.ObdLog

/**
 * Optimisation de l'autoradio (Android 8, sans root ni ADB : seulement ce qu'une appli normale a le droit de faire).
 *  - Mode CarPlay : pendant CarPlay, le Lanceur se fait discret (OBD lu ≈ 3 fois par seconde au lieu d'en continu,
 *    ce qui laisse la puce Bluetooth / Wi-Fi au CarPlay sans fil ; panneau redessiné 2 fois par seconde au plus).
 *  - Nettoyage de la mémoire : ferme les applis qui traînent EN ARRIÈRE-PLAN (jamais celle à l'écran, ni la musique
 *    en cours, ni les services de l'autoradio com.syu / com.fyt, ni CarPlay, ni le clavier). Automatique à l'ouverture
 *    de CarPlay (au plus toutes les 10 min) et, depuis la v0.18, dès que la mémoire libre passe sous 25 %
 *    (surveillance toutes les 30 s par le service, voir autoTick).
 *  - Applis jamais ouvertes depuis 30 jours : à désactiver à la main (bouton qui ouvre leur page Android).
 */
object Optim {
    private var prefs: SharedPreferences? = null

    /** Le Lanceur s'allège pendant CarPlay. */
    var carplayLight by mutableStateOf(true)
        private set
    /** Mémoire libérée automatiquement quand CarPlay s'ouvre. */
    var autoClean by mutableStateOf(true)
        private set
    /** Mémoire surveillée en permanence : libérée toute seule dès qu'elle devient basse (v0.18). */
    var autoRam by mutableStateOf(true)
        private set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("optim", Context.MODE_PRIVATE)
        prefs = p
        carplayLight = p.getBoolean("carplayLight", true)
        autoClean = p.getBoolean("autoClean", true)
        autoRam = p.getBoolean("autoRam", true)
    }

    fun updateAutoRam(v: Boolean) { autoRam = v; prefs?.edit()?.putBoolean("autoRam", v)?.apply() }

    fun updateCarplayLight(v: Boolean) { carplayLight = v; prefs?.edit()?.putBoolean("carplayLight", v)?.apply() }
    fun updateAutoClean(v: Boolean) { autoClean = v; prefs?.edit()?.putBoolean("autoClean", v)?.apply() }

    /** Vrai quand CarPlay est à l'écran (lancé par le dock ou repéré tout seul) et le mode CarPlay activé. */
    fun lightNow(): Boolean = carplayLight && PerfPanel.armed.value && !AppVisibility.foreground.value

    // --- Mémoire ---

    data class Mem(val availMb: Int, val totalMb: Int, val low: Boolean)

    fun mem(ctx: Context): Mem {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Mem((mi.availMem / 1_048_576).toInt(), (mi.totalMem / 1_048_576).toInt(), mi.lowMemory)
    }

    private val KEEP_PREFIX = listOf(
        "com.syu.", "com.fyt.", "com.android.systemui", "com.android.phone", "com.android.bluetooth",
        "com.android.providers.", "com.android.inputmethod", "com.google.android.gms", "com.google.android.gsf",
        "com.android.server", "com.android.settings", "com.android.location", "com.android.nfc",
    )

    /** Paquets à ne jamais fermer : système vital, autoradio, CarPlay / musique / radio choisis, clavier, Lanceur. */
    private fun keep(ctx: Context): Set<String> = buildSet {
        add(ctx.packageName)
        add("android")
        Slot.entries.forEach { s -> (UiPrefs.slots[s] ?: guessPackage(ctx, s))?.let(::add) }
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let(::add)
    }

    @Volatile private var lastCleanMs = 0L

    /**
     * Ferme les processus EN ARRIÈRE-PLAN des autres applis (Android refuse de toucher à ce qui est visible ou joue
     * de la musique). Renvoie le message pour l'écran.
     */
    fun clean(ctx: Context): String {
        val before = mem(ctx)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val keep = keep(ctx)
        var n = 0
        ctx.packageManager.getInstalledApplications(0).forEach { a ->
            val p = a.packageName
            if (p in keep || KEEP_PREFIX.any { p.startsWith(it) } || a.flags and ApplicationInfo.FLAG_PERSISTENT != 0) return@forEach
            runCatching { am.killBackgroundProcesses(p); n++ }
        }
        lastCleanMs = System.currentTimeMillis()
        Thread.sleep(800)
        val after = mem(ctx)
        val gain = (after.availMb - before.availMb).coerceAtLeast(0)
        ObdLog.log("OPTIM", "mémoire : ${before.availMb} → ${after.availMb} Mo libres sur ${after.totalMb}")
        return "Mémoire libre : ${before.availMb} → ${after.availMb} Mo (+$gain Mo) sur ${after.totalMb} Mo."
    }

    // --- Mémoire : surveillance automatique (v0.18) ---

    /** Sous cette part de mémoire libre (25 %, soit ≈ 380 Mo sur 1536), le Lanceur libère la mémoire tout seul. */
    const val AUTO_FREE_PCT = 25
    private const val AUTO_MIN_GAP_MS = 5 * 60_000L
    private const val AUTO_BACKOFF_MS = 20 * 60_000L

    /** Bilan des nettoyages automatiques depuis le démarrage de l'autoradio, pour l'écran. */
    data class AutoStats(val runs: Int = 0, val freedMb: Int = 0, val lastAtMs: Long = 0L, val lastGainMb: Int = 0)

    var autoStats by mutableStateOf(AutoStats())
        private set

    @Volatile private var nextAutoMs = 0L
    @Volatile private var autoRunning = false

    /** Mémoire trop basse pour attendre : vrai sous le seuil ou quand Android lui-même se dit à court. */
    fun needsClean(m: Mem): Boolean = m.low || m.availMb * 100 < m.totalMb * AUTO_FREE_PCT

    /**
     * Appelé toutes les 30 s par le service : si la mémoire libre passe sous le seuil, ferme les applis en
     * arrière-plan (mêmes protections que le bouton « Libérer »). Au plus un nettoyage toutes les 5 min ; s'il n'a
     * presque rien rendu (moins de 15 Mo), le suivant attend 20 min : inutile d'insister, rien ne traîne.
     */
    fun autoTick(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!autoRam || autoRunning || now < nextAutoMs || !needsClean(mem(ctx))) return
        autoRunning = true
        val app = ctx.applicationContext
        Thread({
            try {
                val before = mem(app).availMb
                clean(app)
                val gain = (mem(app).availMb - before).coerceAtLeast(0)
                val s = autoStats
                autoStats = AutoStats(s.runs + 1, s.freedMb + gain, System.currentTimeMillis(), gain)
                nextAutoMs = System.currentTimeMillis() + if (gain < 15) AUTO_BACKOFF_MS else AUTO_MIN_GAP_MS
                ObdLog.log("OPTIM", "nettoyage automatique (mémoire sous $AUTO_FREE_PCT %) : +$gain Mo")
            } catch (_: Exception) {
                nextAutoMs = System.currentTimeMillis() + AUTO_BACKOFF_MS
            } finally {
                autoRunning = false
            }
        }, "nettoyage-auto").start()
    }

    /** Nettoyage automatique à l'ouverture de CarPlay (au plus toutes les 10 min). */
    fun cleanForCarPlay(ctx: Context) {
        if (!autoClean || System.currentTimeMillis() - lastCleanMs < 10 * 60_000L) return
        lastCleanMs = System.currentTimeMillis()
        Thread({ runCatching { clean(ctx.applicationContext) } }, "nettoyage").start()
    }

    // --- Applis inutiles ---

    data class Unused(val pkg: String, val label: String, val lastUsedDays: Int?, val system: Boolean)

    /**
     * Applis avec une icône, pas ouvertes depuis 30 jours (ou jamais), hors autoradio, CarPlay / musique / radio et
     * Lanceur. Sans l'accès aux données d'utilisation : liste vide (on ne sait pas lesquelles servent).
     */
    fun unusedApps(ctx: Context): List<Unused>? {
        if (!fr.p308.lanceur.service.CarPlayWatch.hasAccess(ctx)) return null
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val last = runCatching {
            usm.queryAndAggregateUsageStats(now - 60L * 86_400_000L, now).mapValues { it.value.lastTimeUsed }
        }.getOrDefault(emptyMap())
        val keep = keep(ctx)
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { a -> a.packageName !in keep && KEEP_PREFIX.none { a.packageName.startsWith(it) } }
            .mapNotNull { a ->
                val t = last[a.packageName]?.takeIf { it > 0 }
                val days = t?.let { ((now - it) / 86_400_000L).toInt() }
                if (days != null && days < 30) return@mapNotNull null
                Unused(a.packageName, pm.getApplicationLabel(a).toString(), days, a.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }
            .sortedBy { it.label.lowercase() }
    }

    // --- Réglages Android lisibles ---

    /** Échelles d'animation (fenêtres, transitions, durée) ; 1,0 par défaut, 0,5 conseillé. */
    fun animationScales(ctx: Context): List<Float> = listOf(
        Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE,
    ).map { runCatching { Settings.Global.getFloat(ctx.contentResolver, it) }.getOrDefault(1f) }

    /** Recherche Wi-Fi / Bluetooth permanente (Localisation) : occupe la radio même quand on ne s'en sert pas. */
    fun backgroundScanning(ctx: Context): Pair<Boolean?, Boolean?> {
        fun g(k: String) = runCatching { Settings.Global.getInt(ctx.contentResolver, k) == 1 }.getOrNull()
        return g("wifi_scan_always_enabled") to g("ble_scan_always_enabled")
    }
}
