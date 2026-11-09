package fr.p308.lanceur.sound

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * Volume qui suit la vitesse : plus de bruit de roulement sur voie rapide → la musique monte un peu, puis redescend
 * en ville. Le volume choisi à la main reste la référence : si tu le changes, le Lanceur repart de ta nouvelle valeur.
 * Paliers : 50, 80 et 110 km/h (avec 5 km/h de marge à la descente et 3 s de stabilité, pour ne pas faire du yoyo).
 * Intensité 1 / 2 / 3 = 1 / 2 / 3 crans par palier (crans adaptés si l'autoradio a plus de 15 crans).
 * En pause pendant les sons de test.
 */
object SpeedVolume {
    private val SPEEDS = intArrayOf(50, 80, 110)
    private var base = -1
    private var lastSet = -1
    private var level = 0
    private var pending = 0
    private var pendingSince = 0L
    private var smoothKmh = 0f

    /** Appelé chaque seconde par le service. */
    fun tick(ctx: Context, kmh: Float, nowMs: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val enabled = SoundPrefs.speedVolume && !SoundGen.state.value.playing
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (!enabled) {
            // Désactivé (ou test en cours) : on rend le volume de référence si c'est nous qui l'avions monté.
            if (base >= 0 && cur == lastSet && level > 0 && !SoundGen.state.value.playing) setVol(am, base)
            base = -1; level = 0; lastSet = -1
            return
        }
        // Volume changé à la main (ou premier passage) : nouvelle référence.
        if (base < 0 || cur != lastSet) {
            base = (cur - offset(am, level)).coerceAtLeast(0)
            lastSet = cur
        }
        smoothKmh += (kmh - smoothKmh) * 0.2f
        val up = SPEEDS.count { smoothKmh >= it }
        val down = SPEEDS.count { smoothKmh >= it - 5 }
        val target = when {
            up > level -> up
            down < level -> down
            else -> level
        }
        if (target == level) { pending = level; return }
        if (target != pending) { pending = target; pendingSince = nowMs; return }
        if (nowMs - pendingSince < 3_000L) return
        level = target
        if (base == 0) return // son coupé : on n'y touche pas
        setVol(am, (base + offset(am, level)).coerceAtMost(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
    }

    private fun offset(am: AudioManager, lvl: Int): Int {
        val unit = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 15f).coerceAtLeast(1f)
        return (lvl * SoundPrefs.speedStrength * unit).roundToInt()
    }

    private fun setVol(am: AudioManager, v: Int) {
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        lastSet = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
}
