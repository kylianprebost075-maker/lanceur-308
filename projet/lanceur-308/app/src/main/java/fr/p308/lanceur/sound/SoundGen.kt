package fr.p308.lanceur.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** Côté joué : gauche, droite ou les deux. */
enum class Side(val label: String) { LEFT("Gauche"), RIGHT("Droite"), BOTH("Les deux") }

data class PlayState(val playing: Boolean = false, val title: String = "", val detail: String = "")

/** Une source de son : remplit des échantillons stéréo entrelacés (-1..1), un appel après l'autre. */
interface Signal {
    val title: String
    /** Remplit `out` (frames × 2) ; renvoie un texte d'état à afficher (ou null pour ne pas le changer). Fin = false. */
    fun fill(out: FloatArray, frames: Int): Boolean
    val detail: String get() = ""
}

/**
 * Sons de test et de mesure joués par l'autoradio (48 kHz, stéréo, 16 bits). Le micro du PC enregistre à la place
 * du conducteur ; l'outil `outils/son/mesure_son.py` analyse. La musique en cours est mise en pause le temps du test.
 * Le côté gauche / droit se choisit ici ; l'avant / arrière se choisit avec le fader de l'autoradio.
 */
object SoundGen {
    const val RATE = 48_000
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var focus: AudioFocusRequest? = null

    fun play(ctx: Context, signal: Signal) {
        stop(ctx)
        val am = ctx.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            .also { runCatching { am.requestAudioFocus(it) } }
        running = true
        _state.value = PlayState(true, signal.title, signal.detail)
        thread = Thread({
            val frames = 2048
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(frames * 4 * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val buf = FloatArray(frames * 2)
            val pcm = ShortArray(frames * 2)
            try {
                track.play()
                var lastDetail = signal.detail
                while (running) {
                    val more = signal.fill(buf, frames)
                    for (i in pcm.indices) pcm[i] = (buf[i].coerceIn(-1f, 1f) * 32_000f).roundToInt().toShort()
                    track.write(pcm, 0, pcm.size)
                    if (signal.detail != lastDetail) {
                        lastDetail = signal.detail
                        _state.value = PlayState(true, signal.title, lastDetail)
                    }
                    if (!more) break
                }
                // Laisse finir ce qui est dans la mémoire tampon (fin d'une séquence).
                if (running) Thread.sleep(300)
            } catch (_: Exception) {
            } finally {
                runCatching { track.stop() }
                track.release()
                running = false
                if (signal is Saturation) signal.restore()
                focus?.let { f -> runCatching { am.abandonAudioFocusRequest(f) } }
                _state.value = PlayState()
            }
        }, "son-test").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop(ctx: Context? = null) {
        running = false
        thread?.join(1500)
        thread = null
    }

    // --- Signaux ---

    /** Sinusoïde ; `invertRight` = droite en opposition de phase. */
    class Tone(private val freq: Double, private val side: Side, private val amp: Float = 0.5f,
               override val title: String = "Son pur ${freq.toInt()} Hz — ${side.label}") : Signal {
        private var phase = 0.0
        override fun fill(out: FloatArray, frames: Int): Boolean {
            val step = 2 * PI * freq / RATE
            for (i in 0 until frames) {
                val v = (sin(phase) * amp).toFloat()
                phase += step
                if (phase > 2 * PI) phase -= 2 * PI
                out[2 * i] = if (side != Side.RIGHT) v else 0f
                out[2 * i + 1] = if (side != Side.LEFT) v else 0f
            }
            return true
        }
    }

    /** Bruit rose (même énergie par octave, filtre de Paul Kellet), le même sur les deux voies. -12 dB environ. */
    class Pink(private val side: Side, private val lowPassHz: Double? = null,
               override val title: String = "Bruit rose — ${side.label}") : Signal {
        private val rnd = java.util.Random(1)
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var b3 = 0.0
        private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
        private var lp = 0.0
        private val a = lowPassHz?.let { exp(-2 * PI * it / RATE) }

        fun next(): Float {
            val w = rnd.nextGaussian() * 0.1
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520; b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
            var v = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.55
            b6 = w * 0.115926
            if (a != null) { lp = a * lp + (1 - a) * v; v = lp * 2.5 }
            return v.toFloat()
        }

        override fun fill(out: FloatArray, frames: Int): Boolean {
            for (i in 0 until frames) {
                val v = next()
                out[2 * i] = if (side != Side.RIGHT) v else 0f
                out[2 * i + 1] = if (side != Side.LEFT) v else 0f
            }
            return true
        }
    }

    /** Balayage logarithmique 20 Hz → 20 kHz en 8 s, puis 2 s de silence, en boucle. */
    class Sweep(private val side: Side, override val title: String = "Balayage 20 Hz → 20 kHz — ${side.label}") : Signal {
        private var n = 0L
        private val len = 8 * RATE
        private val period = 10 * RATE
        private val k = ln(20_000.0 / 20.0)
        override fun fill(out: FloatArray, frames: Int): Boolean {
            for (i in 0 until frames) {
                val t = (n % period).toInt()
                val v = if (t < len) {
                    val x = t.toDouble() / len
                    val ph = 2 * PI * 20.0 * (len.toDouble() / RATE) / k * (exp(x * k) - 1)
                    // Fondu de 10 ms au début et à la fin (pas de clic).
                    val fade = minOf(1.0, t / 480.0, (len - t) / 480.0)
                    (sin(ph) * 0.5 * fade).toFloat()
                } else 0f
                out[2 * i] = if (side != Side.RIGHT) v else 0f
                out[2 * i + 1] = if (side != Side.LEFT) v else 0f
                n++
            }
            return true
        }
    }

    /**
     * Clics pour vérifier l'alignement au micro : clic à GAUCHE, puis exactement 500 ms après un clic à DROITE, toutes
     * les 1,5 s. Le PC mesure l'écart réel : s'il diffère de 500 ms, un côté arrive en avance.
     */
    class Clicks(override val title: String = "Clics gauche / droite (alignement)") : Signal {
        private var n = 0L
        private val period = (1.5 * RATE).toInt()
        private val gap = RATE / 2
        private val click = FloatArray(240) { i ->
            // Ondelette de Ricker centrée sur 3 kHz : un clic net, sans grave qui traîne.
            val t = (i - 120).toDouble() / RATE
            val a = PI * 3000.0 * t
            ((1 - 2 * a * a) * exp(-a * a) * 0.8).toFloat()
        }
        override fun fill(out: FloatArray, frames: Int): Boolean {
            for (i in 0 until frames) {
                val t = (n % period).toInt()
                out[2 * i] = if (t < click.size) click[t] else 0f
                out[2 * i + 1] = if (t in gap until gap + click.size) click[t - gap] else 0f
                n++
            }
            return true
        }
    }

    /**
     * Test de polarité : basses (bruit rose sous 200 Hz) sur les deux côtés, 4 s « A » en phase puis 4 s « B » avec la
     * droite inversée. Branchement correct : A est plus plein et centré. Si B a plus de basses, un haut-parleur est
     * branché à l'envers (+ et − inversés).
     */
    class Polarity(override val title: String = "Test de polarité (A / B)") : Signal {
        private val pink = Pink(Side.BOTH, lowPassHz = 200.0)
        private var n = 0L
        private val half = 4 * RATE
        override var detail: String = "A : en phase"
        override fun fill(out: FloatArray, frames: Int): Boolean {
            for (i in 0 until frames) {
                val inverted = (n / half) % 2 == 1L
                val gapNow = (n % half) < RATE / 4 // 250 ms de silence entre A et B
                val v = if (gapNow) 0f else pink.next()
                out[2 * i] = v
                out[2 * i + 1] = if (inverted) -v else v
                n++
            }
            detail = if ((n / half) % 2 == 1L) "B : droite inversée" else "A : en phase"
            return true
        }
    }

    /**
     * Recherche de la saturation de l'ampli : 3 s de silence, puis à chaque cran de volume (du cran de départ au
     * maximum) 2 s de son pur à 400 Hz (-3 dB, comme une musique forte) et 1 s de silence. Le PC calcule la distorsion
     * de chaque palier : le premier qui dépasse 3 % est le volume à ne pas dépasser. Le volume d'avant est remis à la fin.
     */
    class Saturation(ctx: Context, private val fromStep: Int) : Signal {
        private val am = ctx.getSystemService(AudioManager::class.java)
        private val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        override val title = "Recherche de la saturation"
        private var n = 0L
        private var phase = 0.0
        private var step = -1
        override var detail = "Départ dans 3 s"

        override fun fill(out: FloatArray, frames: Int): Boolean {
            for (i in 0 until frames) {
                val t = n - 3 * RATE
                var v = 0f
                if (t >= 0) {
                    val k = (t / (3 * RATE)).toInt()
                    val cran = fromStep + k
                    if (cran > max) return false.also { out.fill(0f, 2 * i) }
                    if (k != step) {
                        step = k
                        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, cran, 0) }
                        detail = "Palier ${k + 1} : volume $cran / $max"
                    }
                    if (t % (3 * RATE) < 2 * RATE) {
                        v = (sin(phase) * 0.7).toFloat()
                        phase += 2 * PI * 400.0 / RATE
                        if (phase > 2 * PI) phase -= 2 * PI
                    }
                }
                out[2 * i] = v
                out[2 * i + 1] = v
                n++
            }
            return true
        }

        fun restore() {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
        }
    }
}
