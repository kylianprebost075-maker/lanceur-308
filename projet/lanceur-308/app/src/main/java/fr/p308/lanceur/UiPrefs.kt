package fr.p308.lanceur

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.p308.lanceur.ui.NightMode
import kotlinx.coroutines.flow.MutableStateFlow

data class CarColor(val name: String, val argb: Long)

val CAR_COLORS = listOf(
    CarColor("Gris Aluminium", 0xFFB9BDC3),
    CarColor("Gris Fer", 0xFF6C7177),
    CarColor("Noir Perla", 0xFF1D1E22),
    CarColor("Blanc Banquise", 0xFFF1F1EC),
    CarColor("Rouge Lucifer", 0xFFB3121B),
    CarColor("Bleu Récif", 0xFF1F4E8C),
)

/** Raccourcis du dock : chaque emplacement pointe vers un paquet installé. */
enum class Slot(val label: String, val guesses: List<String>) {
    CARPLAY("CarPlay", listOf("zlink", "carplay", "autokit", "carbit")),
    MUSIC("Musique", listOf("music", "media")),
    RADIO("Radio", listOf("radio")),
}

object AppVisibility {
    val foreground = MutableStateFlow(false)
}

/**
 * Panneau « performances » affiché par-dessus CarPlay : armé quand CarPlay est lancé depuis le dock avec l'option
 * activée, désarmé au retour dans le Lanceur ou par un appui long sur le panneau.
 */
object PerfPanel {
    val armed = MutableStateFlow(false)
}

/** Taille du panneau CarPlay. */
enum class PerfSize(val label: String) { FULL("Complet"), COMPACT("Réduit"), AUTO("Auto") }

object UiPrefs {
    private lateinit var prefs: SharedPreferences

    var colorIndex by mutableIntStateOf(0)
        private set
    var fiveDoors by mutableStateOf(true)
        private set
    val slots = mutableStateMapOf<Slot, String>()
    var nightMode by mutableStateOf(NightMode.AUTO)
        private set
    var dimAtNight by mutableStateOf(true)
        private set
    /** v0.9 : panneau performances à côté de CarPlay. */
    var perfOnCarPlay by mutableStateOf(true)
        private set
    /** Côté du panneau performances : droite (par défaut) ou gauche. */
    var perfOnRight by mutableStateOf(true)
        private set
    /**
     * Taille du panneau CarPlay : colonne complète, pastille en haut (v0.12.3), ou automatique (v0.13 : complète
     * pendant la chauffe ou en cas d'alerte, pastille une fois le moteur chaud).
     */
    var perfSize by mutableStateOf(PerfSize.AUTO)
        private set
    /** v0.13 : le panneau s'affiche aussi quand CarPlay s'ouvre tout seul (accès aux données d'utilisation). */
    var perfAutoCarPlay by mutableStateOf(true)
        private set
    /** v0.12 : style « Verre » (panneaux translucides sur fond dégradé) ou style classique opaque. */
    var glass by mutableStateOf(true)
        private set

    val carColor get() = CAR_COLORS[colorIndex.coerceIn(CAR_COLORS.indices)]

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        colorIndex = prefs.getInt("color", CAR_COLORS.indexOfFirst { it.name == "Noir Perla" })
        fiveDoors = prefs.getBoolean("fiveDoors", true)
        nightMode = runCatching { NightMode.valueOf(prefs.getString("nightMode", "AUTO")!!) }.getOrDefault(NightMode.AUTO)
        dimAtNight = prefs.getBoolean("dimAtNight", true)
        perfOnCarPlay = prefs.getBoolean("perfOnCarPlay", true)
        perfOnRight = prefs.getBoolean("perfOnRight", true)
        perfSize = runCatching { PerfSize.valueOf(prefs.getString("perfSize", null)!!) }.getOrElse {
            // Réglage de la v0.12.3 (réduit oui / non) repris s'il existe.
            if (prefs.contains("perfCompact")) (if (prefs.getBoolean("perfCompact", false)) PerfSize.COMPACT else PerfSize.FULL)
            else PerfSize.AUTO
        }
        perfAutoCarPlay = prefs.getBoolean("perfAutoCarPlay", true)
        glass = prefs.getBoolean("glass", true)
        Slot.entries.forEach { s -> prefs.getString("slot_${s.name}", null)?.let { slots[s] = it } }
    }

    fun setColor(i: Int) {
        colorIndex = i
        prefs.edit().putInt("color", i).apply()
    }

    fun updateFiveDoors(v: Boolean) {
        fiveDoors = v
        prefs.edit().putBoolean("fiveDoors", v).apply()
    }

    fun updateNightMode(m: NightMode) {
        nightMode = m
        prefs.edit().putString("nightMode", m.name).apply()
    }

    fun updateDimAtNight(v: Boolean) {
        dimAtNight = v
        prefs.edit().putBoolean("dimAtNight", v).apply()
    }

    fun updatePerfOnCarPlay(v: Boolean) {
        perfOnCarPlay = v
        prefs.edit().putBoolean("perfOnCarPlay", v).apply()
    }

    fun updatePerfSize(v: PerfSize) {
        perfSize = v
        prefs.edit().putString("perfSize", v.name).apply()
    }

    fun updatePerfAutoCarPlay(v: Boolean) {
        perfAutoCarPlay = v
        prefs.edit().putBoolean("perfAutoCarPlay", v).apply()
    }

    fun updatePerfOnRight(v: Boolean) {
        perfOnRight = v
        prefs.edit().putBoolean("perfOnRight", v).apply()
    }

    fun updateGlass(v: Boolean) {
        glass = v
        prefs.edit().putBoolean("glass", v).apply()
    }

    fun setSlot(slot: Slot, pkg: String) {
        slots[slot] = pkg
        prefs.edit().putString("slot_${slot.name}", pkg).apply()
    }
}

fun launchPackage(ctx: Context, pkg: String): Boolean {
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
    return true
}

/** Lance l'appli assignée à l'emplacement, ou devine-la d'après le nom du paquet. */
fun launchSlot(ctx: Context, slot: Slot): Boolean {
    val pkg = UiPrefs.slots[slot] ?: guessPackage(ctx, slot)?.also { UiPrefs.setSlot(slot, it) }
    PerfPanel.armed.value = slot == Slot.CARPLAY && UiPrefs.perfOnCarPlay
    if (pkg != null && launchPackage(ctx, pkg)) return true
    PerfPanel.armed.value = false
    Toast.makeText(ctx, "Appui long sur une appli dans « Applis » pour l'assigner à ${slot.label}", Toast.LENGTH_LONG).show()
    return false
}

fun guessPackage(ctx: Context, slot: Slot): String? {
    val launchable = ctx.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }
        .filter { it != ctx.packageName }
    return slot.guesses.firstNotNullOfOrNull { g -> launchable.firstOrNull { it.contains(g, ignoreCase = true) } }
}

/** Active ou désactive l'alias HOME : c'est ce qui fait de l'appli un écran d'accueil possible. */
object HomeAlias {
    private fun component(ctx: Context) = ComponentName(ctx, "${ctx.packageName}.HomeAlias")

    fun isEnabled(ctx: Context) =
        ctx.packageManager.getComponentEnabledSetting(component(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(ctx: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ctx.packageManager.setComponentEnabledSetting(component(ctx), state, PackageManager.DONT_KILL_APP)
    }
}
