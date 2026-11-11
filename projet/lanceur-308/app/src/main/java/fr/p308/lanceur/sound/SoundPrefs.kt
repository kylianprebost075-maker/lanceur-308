package fr.p308.lanceur.sound

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Haut-parleurs de la voiture, dans l'ordre des réglages. */
enum class Speaker(val label: String, val short: String) {
    FL("Avant gauche (tweeter Focal)", "AVG"),
    FR("Avant droit (tweeter Focal)", "AVD"),
    RL("Arrière gauche (Pioneer)", "ARG"),
    RR("Arrière droit (Pioneer)", "ARD"),
}

/** Réglages du son (préférences « sound », sauvegardées sur la clé avec le reste). */
object SoundPrefs {
    private var prefs: SharedPreferences? = null

    /** Distances oreille du conducteur → haut-parleur, en cm (0 = pas encore mesurée). */
    val distancesCm = mutableStateListOf(0, 0, 0, 0)

    /** Volume qui monte avec la vitesse (bruit de roulement). */
    var speedVolume by mutableStateOf(false)
        private set
    /** Intensité 1 (léger) à 3 (fort). */
    var speedStrength by mutableIntStateOf(2)
        private set
    /** v0.30 : basses selon le volume (BassAuto), intensité 1 à 3, fréquence qui fait vibrer les portes (0 = inconnue). */
    var bassAuto by mutableStateOf(false)
        private set
    var bassStrength by mutableIntStateOf(2)
        private set
    var resonanceHz by mutableIntStateOf(0)
        private set
    /** v0.31 : balance réglée pour le conducteur seul ou pour tout le monde. */
    var seatMode by mutableStateOf(SeatMode.SOLO)
        private set
    /** Annonces vocales des alertes. */
    var voice by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("sound", Context.MODE_PRIVATE)
        prefs = p
        Speaker.entries.forEachIndexed { i, s -> distancesCm[i] = p.getInt("dist_${s.name}", 0) }
        speedVolume = p.getBoolean("speedVolume", false)
        speedStrength = p.getInt("speedStrength", 2).coerceIn(1, 3)
        voice = p.getBoolean("voice", false)
        bassAuto = p.getBoolean("bassAuto", false)
        bassStrength = p.getInt("bassStrength", 2).coerceIn(1, 3)
        resonanceHz = p.getInt("resonanceHz", 0)
        seatMode = runCatching { SeatMode.valueOf(p.getString("seatMode", null)!!) }.getOrDefault(SeatMode.SOLO)
        seatAuto = p.getBoolean("seatAuto", false)
        autoPassengers = p.getBoolean("autoPassengers", true)
        soloChamp = if (p.contains("soloRight")) p.getInt("soloRight", 0) to p.getInt("soloFront", 0) else null
    }

    /** [auto] = choisi par l'appli (porte passager), pas par l'utilisateur. */
    fun updateSeatMode(v: SeatMode, auto: Boolean = false) {
        seatMode = v
        seatAuto = auto
        prefs?.edit()?.putString("seatMode", v.name)?.putBoolean("seatAuto", auto)?.apply()
    }

    /** v0.32 : « Passagers » mis automatiquement (ramené à « Seul » au démarrage suivant). */
    var seatAuto = false
        private set
    /** v0.32 : passer seul en « Passagers » quand une porte passager s'ouvre. */
    var autoPassengers by mutableStateOf(true)
        private set
    fun updateAutoPassengers(v: Boolean) { autoPassengers = v; prefs?.edit()?.putBoolean("autoPassengers", v)?.apply() }

    /** v0.31 : position « Seul » de la croix Champ trouvée au micro du PC (null = d'après les distances). */
    var soloChamp by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    fun updateSoloChamp(right: Int, front: Int) {
        soloChamp = right.coerceIn(-8, 8) to front.coerceIn(-8, 8)
        prefs?.edit()?.putInt("soloRight", soloChamp!!.first)?.putInt("soloFront", soloChamp!!.second)?.apply()
    }

    fun updateBassAuto(v: Boolean) { bassAuto = v; prefs?.edit()?.putBoolean("bassAuto", v)?.apply() }
    fun updateBassStrength(v: Int) { bassStrength = v.coerceIn(1, 3); prefs?.edit()?.putInt("bassStrength", bassStrength)?.apply() }
    fun updateResonance(hz: Int) { resonanceHz = hz.coerceIn(0, 200); prefs?.edit()?.putInt("resonanceHz", resonanceHz)?.apply() }

    fun setDistance(s: Speaker, cm: Int) {
        distancesCm[s.ordinal] = cm.coerceIn(0, 400)
        prefs?.edit()?.putInt("dist_${s.name}", distancesCm[s.ordinal])?.apply()
    }

    fun updateSpeedVolume(v: Boolean) {
        speedVolume = v
        prefs?.edit()?.putBoolean("speedVolume", v)?.apply()
    }

    fun updateSpeedStrength(v: Int) {
        speedStrength = v.coerceIn(1, 3)
        prefs?.edit()?.putInt("speedStrength", speedStrength)?.apply()
    }

    fun updateVoice(v: Boolean) {
        voice = v
        prefs?.edit()?.putBoolean("voice", v)?.apply()
    }
}

/** Réglage d'un haut-parleur : retard à ajouter et niveau à baisser pour qu'il « arrive » en même temps que le plus loin. */
data class SpeakerSetting(val speaker: Speaker, val distanceCm: Int, val delayMs: Double, val delayCm: Int, val levelDb: Double)

/**
 * Alignement temporel : le son du haut-parleur le plus proche arrive le premier et « tire » l'image vers lui (côté
 * portière conducteur). On retarde chaque haut-parleur de (distance du plus loin − sa distance) / vitesse du son,
 * et on baisse les plus proches de 20·log10(d / d_max) dB (un haut-parleur 2 fois plus près paraît 6 dB plus fort).
 */
object TimeAlignment {
    /** Vitesse du son dans l'habitacle (≈ 20 °C), en cm par milliseconde. */
    const val SOUND_CM_PER_MS = 34.3

    fun compute(distancesCm: List<Int>): List<SpeakerSetting>? {
        val known = Speaker.entries.filter { distancesCm.getOrElse(it.ordinal) { 0 } > 0 }
        if (known.size < 2) return null
        val max = known.maxOf { distancesCm[it.ordinal] }
        return known.map { s ->
            val d = distancesCm[s.ordinal]
            SpeakerSetting(
                s, d,
                delayMs = (max - d) / SOUND_CM_PER_MS,
                delayCm = max - d,
                levelDb = 20 * kotlin.math.log10(d.toDouble() / max),
            )
        }
    }
}
