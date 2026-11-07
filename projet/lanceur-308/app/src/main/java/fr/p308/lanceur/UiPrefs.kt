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

    val carColor get() = CAR_COLORS[colorIndex.coerceIn(CAR_COLORS.indices)]

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        colorIndex = prefs.getInt("color", CAR_COLORS.indexOfFirst { it.name == "Noir Perla" })
        fiveDoors = prefs.getBoolean("fiveDoors", true)
        nightMode = runCatching { NightMode.valueOf(prefs.getString("nightMode", "AUTO")!!) }.getOrDefault(NightMode.AUTO)
        dimAtNight = prefs.getBoolean("dimAtNight", true)
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
    if (pkg != null && launchPackage(ctx, pkg)) return true
    Toast.makeText(ctx, "Appui long sur une appli dans « Applis » pour l'assigner à ${slot.label}", Toast.LENGTH_LONG).show()
    return false
}

private fun guessPackage(ctx: Context, slot: Slot): String? {
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
