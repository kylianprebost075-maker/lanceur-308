package fr.p308.lanceur.sound

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Courbe des basses selon le volume (v0.30), calcul pur vérifié par BassCurveTest.
 *
 * - Volume bas : l'oreille entend beaucoup moins les graves (courbes d'égale sonie) → on les monte, jusqu'à
 *   [BOOST_DB] au plus bas, plus rien à partir de [BOOST_END] du volume.
 * - Volume fort : les portes vibrent sur une fréquence précise (trouvée avec [ResonanceSweep]) → la bande qui la
 *   contient est baissée, de 0 à partir de [CUT_START] du volume jusqu'à [CUT_DB] au maximum.
 * Sans caisson, les woofers des portes font tout le grave : on ne monte jamais sous 40 Hz (ils ne le jouent pas et
 * ça fait vibrer pour rien).
 */
object BassCurve {
    const val BOOST_DB = 6.0
    const val BOOST_END = 0.6
    const val CUT_DB = 6.0
    const val CUT_START = 0.5
    /** Graves concernés par la remontée à volume bas. */
    const val BASS_TOP_HZ = 250.0
    const val BASS_FLOOR_HZ = 40.0

    /**
     * Gain en dB de chaque bande (centres en Hz) pour un volume [volume] entre 0 et 1, [strength] 1 à 3 (doux à fort),
     * et la fréquence qui fait vibrer les portes ([resonanceHz], 0 = inconnue).
     */
    fun gains(centersHz: List<Double>, volume: Double, resonanceHz: Int, strength: Int = 2): List<Double> {
        val v = volume.coerceIn(0.0, 1.0)
        val k = strength.coerceIn(1, 3) / 2.0
        val boost = BOOST_DB * k * ((BOOST_END - v) / BOOST_END).coerceIn(0.0, 1.0)
        val cut = CUT_DB * k * ((v - CUT_START) / (1 - CUT_START)).coerceIn(0.0, 1.0)
        // Bande la plus proche de la vibration (en octaves).
        val resonant = if (resonanceHz > 0) centersHz.indices.minByOrNull { abs(ln(centersHz[it] / resonanceHz)) } else null
        return centersHz.mapIndexed { i, hz ->
            var g = 0.0
            if (hz in BASS_FLOOR_HZ..BASS_TOP_HZ) g += boost * (if (hz <= 120.0) 1.0 else 0.5)
            if (i == resonant) g -= cut
            g
        }
    }
}

/** Montée lente des basses, 30 → 150 Hz en 30 s, sur les deux côtés : on note la fréquence où les portes vibrent. */
class ResonanceSweep(override val title: String = "Recherche de la vibration (30 → 150 Hz)") : Signal {
    private var n = 0L
    private var phase = 0.0
    private val len = 30L * SoundGen.RATE
    @Volatile var currentHz = 30.0
        private set
    override var detail = "30 Hz"

    override fun fill(out: FloatArray, frames: Int): Boolean {
        for (i in 0 until frames) {
            if (n >= len) return false.also { out.fill(0f, 2 * i) }
            val x = n.toDouble() / len
            currentHz = 30.0 * Math.pow(5.0, x) // 30 → 150 Hz, montée régulière en octaves
            phase += 2 * Math.PI * currentHz / SoundGen.RATE
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI
            val fade = minOf(1.0, n / 4800.0, (len - n) / 4800.0)
            val s = (Math.sin(phase) * 0.6 * fade).toFloat()
            out[2 * i] = s; out[2 * i + 1] = s
            n++
        }
        detail = "${currentHz.roundToInt()} Hz"
        return true
    }
}

/**
 * Applique la courbe à tout le son d'Android (égaliseur global, session 0) : musique, CarPlay par l'appli de
 * l'autoradio, Lanceur. La radio FM de l'autoradio passe ailleurs et n'est pas touchée. Le volume lu est celui de
 * l'autoradio (service FYT, module son 4, code 2 : le chiffre de la barre du haut), sinon celui d'Android.
 * Si l'autoradio refuse l'égaliseur global, l'état le dit et rien n'est changé.
 */
object BassAuto {
    enum class Status(val label: String) {
        OFF("Coupé"), ON("Actif"), UNSUPPORTED("Cet autoradio refuse qu'une appli règle les basses de tout le son"),
    }

    /** Volume de l'autoradio (service FYT), -1 tant qu'il n'a rien dit. */
    @Volatile var fytVolume = -1
    /** Plus haut volume FYT vu (les autoradios FYT vont en général jusqu'à 40). */
    @Volatile var fytMax = 40

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _gains = MutableStateFlow<List<Pair<Int, Double>>>(emptyList())
    /** Bandes (Hz) et gains (dB) appliqués en ce moment, pour l'écran. */
    val gains: StateFlow<List<Pair<Int, Double>>> = _gains.asStateFlow()

    private var eq: Equalizer? = null
    private var lastKey = ""

    fun onFytVolume(v: Int) {
        if (v < 0) return
        fytVolume = v
        if (v > fytMax) fytMax = v
    }

    private fun volumeFraction(ctx: Context): Double {
        if (fytVolume >= 0) return fytVolume.toDouble() / fytMax
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0.5
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    /** Appelé chaque seconde par le service. */
    fun tick(ctx: Context) {
        if (!SoundPrefs.bassAuto) { release(); _status.value = Status.OFF; return }
        if (_status.value == Status.UNSUPPORTED) return
        val e = eq ?: runCatching { Equalizer(0, 0).also { it.enabled = true } }.getOrNull().also { eq = it }
        if (e == null) { _status.value = Status.UNSUPPORTED; return }
        val vol = volumeFraction(ctx)
        val key = "%.2f/%d/%d".format(vol, SoundPrefs.resonanceHz, SoundPrefs.bassStrength)
        if (key == lastKey && _status.value == Status.ON) return
        runCatching {
            val bands = e.numberOfBands.toInt()
            val centers = (0 until bands).map { e.getCenterFreq(it.toShort()) / 1000.0 }
            val (lo, hi) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            val g = BassCurve.gains(centers, vol, SoundPrefs.resonanceHz, SoundPrefs.bassStrength)
            g.forEachIndexed { i, db -> e.setBandLevel(i.toShort(), (db * 100).roundToInt().coerceIn(lo, hi).toShort()) }
            e.enabled = true
            _gains.value = centers.map { it.roundToInt() }.zip(g)
            lastKey = key
            _status.value = Status.ON
        }.onFailure { release(); _status.value = Status.UNSUPPORTED }
    }

    fun release() {
        eq?.let { runCatching { it.enabled = false; it.release() } }
        eq = null
        lastKey = ""
        _gains.value = emptyList()
    }
}
