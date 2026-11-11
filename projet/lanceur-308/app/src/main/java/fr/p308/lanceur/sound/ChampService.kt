package fr.p308.lanceur.sound

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pour qui le son est réglé (v0.31). */
enum class SeatMode(val label: String) { SOLO("Seul"), PASSENGERS("Passagers") }

/**
 * Touches à faire dans l'onglet « Champ » de l'égaliseur FYT (com.syu.eq) pour placer le point de la croix balance /
 * fader : onglet Champ, « Tous » (centre), puis les flèches. Positions relevées sur l'autoradio de l'auteur
 * (1280 × 720, 11/11) : onglet (1100, 680), Tous (335, 498), → (1150, 356), ↑ (941, 168), ← (730, 356), ↓ (941, 544)
 * (← et ↓ vérifiés sur l'autoradio le 11/11 : Tous, ← ×2, ↓ ×2 donne bien −2, −2). Sur un autre écran, les positions
 * sont mises à l'échelle. Calcul pur, vérifié par ChampTapsTest.
 * Relevé du même jour : l'égaliseur publie la position sur le service FYT, module 4 code 8 = [8 + crans →, 8 − crans ↑]
 * (centre [8, 8]). Lecture seulement : aucune commande n'est envoyée au service.
 */
object ChampTaps {
    const val REF_W = 1280
    const val REF_H = 720
    val TAB = 1100 to 680
    val ALL = 335 to 498
    val RIGHT = 1150 to 356
    val LEFT = 730 to 356
    val UP = 941 to 168
    val DOWN = 941 to 544

    fun taps(right: Int, front: Int, w: Int = REF_W, h: Int = REF_H): List<Pair<Int, Int>> {
        val r = right.coerceIn(-FytStage.MAX_STEPS, FytStage.MAX_STEPS)
        val f = front.coerceIn(-FytStage.MAX_STEPS, FytStage.MAX_STEPS)
        val raw = listOf(TAB, ALL) + List(kotlin.math.abs(r)) { if (r > 0) RIGHT else LEFT } +
            List(kotlin.math.abs(f)) { if (f > 0) UP else DOWN }
        return raw.map { (x, y) -> x * w / REF_W to y * h / REF_H }
    }

    /**
     * Position de la croix pour chaque mode : passagers = centre ; seul = celle trouvée au micro du PC si elle existe
     * ([soloMeasured], envoyée par reglage_auto.py), sinon d'après les distances, sinon 1 → et 3 ↑.
     */
    fun position(mode: SeatMode, distancesCm: List<Int>, soloMeasured: Pair<Int, Int>? = null): Pair<Int, Int> = when (mode) {
        SeatMode.PASSENGERS -> 0 to 0
        SeatMode.SOLO -> soloMeasured ?: FytStage.plan(distancesCm)?.let { it.rightSteps to it.frontSteps } ?: (1 to 3)
    }
}

/**
 * Service d'accessibilité (activé à la main par l'utilisateur, Android → Accessibilité) qui place la balance de
 * l'autoradio : il ouvre l'égaliseur FYT, touche les boutons comme un doigt, puis revient. Il n'agit QUE quand
 * l'utilisateur appuie sur « Seul » ou « Passagers » (ou, plus tard, sur décision automatique annoncée à l'écran),
 * uniquement dans com.syu.eq, et ne lit rien d'autre.
 */
class ChampService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.packageName != EQ_PKG) return
        val target = pending ?: return
        pending = null
        // Taille réelle de l'écran (les touches sont en coordonnées d'écran, barres comprises).
        val m = android.util.DisplayMetrics().also { getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it) }
        val taps = ChampTaps.taps(target.first, target.second, m.widthPixels, m.heightPixels).toMutableList()
        // Onglet et « Tous » trouvés par leur texte si l'égaliseur les expose (plus sûr que la position).
        findText("Champ")?.let { taps[0] = it }
        handler.postDelayed({
            runTaps(taps, 0) {
                _status.value = "Balance réglée : ${describe(target)}"
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 600)
            }
        }, 1200)
    }

    private fun runTaps(list: List<Pair<Int, Int>>, i: Int, done: () -> Unit) {
        if (i >= list.size) { done(); return }
        var (x, y) = list[i]
        if (i == 1) findText("Tous")?.let { x = it.first; y = it.second }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        dispatchGesture(g, null, null)
        handler.postDelayed({ runTaps(list, i + 1, done) }, if (i == 0) 900 else 450)
    }

    private fun findText(text: String): Pair<Int, Int>? = runCatching {
        val node: AccessibilityNodeInfo = rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }
            ?: return null
        val r = android.graphics.Rect().also(node::getBoundsInScreen)
        r.centerX() to r.centerY()
    }.getOrNull()

    companion object {
        const val EQ_PKG = "com.syu.eq"
        @Volatile private var instance: ChampService? = null
        @Volatile private var pending: Pair<Int, Int>? = null
        private val _status = MutableStateFlow("")
        val status: StateFlow<String> = _status.asStateFlow()

        fun describe(p: Pair<Int, Int>): String = if (p.first == 0 && p.second == 0) "point au centre, bouton « Tous »" else
            "${kotlin.math.abs(p.first)} cran(s) ${if (p.first >= 0) "→" else "←"}, ${kotlin.math.abs(p.second)} cran(s) ${if (p.second >= 0) "↑" else "↓"}"

        /** Le service est-il activé dans Android → Accessibilité ? */
        fun enabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, ChampService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /** Applique le mode. Renvoie un message pour l'écran. */
        fun apply(ctx: Context, mode: SeatMode): String {
            val pos = ChampTaps.position(mode, SoundPrefs.distancesCm.toList(), SoundPrefs.soloChamp)
            val launch = ctx.packageManager.getLaunchIntentForPackage(EQ_PKG)
                ?: return "Égaliseur FYT introuvable : règle la balance à la main (${describe(pos)})"
            if (!enabled(ctx) || instance == null) {
                return "Active d'abord « Lanceur 308 » dans Android → Accessibilité (une seule fois), puis réessaie. " +
                    "À la main : ${describe(pos)}"
            }
            pending = pos
            _status.value = "Réglage en cours…"
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Réglage de la balance : ${describe(pos)}"
        }

        fun openSettings(ctx: Context) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
