package fr.p308.lanceur.sound

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * v0.35, ESSAI seulement : enregistre le micro de l'autoradio dans un fichier WAV, pour voir sur le PC si on peut
 * distinguer « quelqu'un parle » de « quelqu'un chante » par-dessus la musique. Rien dans l'appli ne le lance : seul le
 * PC branché en ADB peut le faire (outils/adb/essai_micro.ps1), et l'autorisation du micro n'est jamais demandée à
 * l'écran. Le fichier reste sur l'autoradio (dossier de l'appli) jusqu'à ce que le PC le récupère ; 6 minutes au plus.
 */
object MicTest {
    const val MAX_SECONDS = 360
    const val WAV = "essai-micro.wav"
    const val STATUS = "essai-micro.txt"
    private val RATES = intArrayOf(16000, 44100, 48000, 8000)

    @Volatile private var running = false
    private var thread: Thread? = null

    /** En-tête WAV (PCM 16 bits mono) pour [dataBytes] octets de son. */
    fun wavHeader(rate: Int, dataBytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    private fun status(dir: File, text: String) = runCatching { File(dir, STATUS).writeText(text + "\n") }

    /** Démarre l'enregistrement ([source] : 1 = micro, 6 = reconnaissance vocale). Sans effet s'il tourne déjà. */
    fun start(ctx: Context, source: Int = MediaRecorder.AudioSource.MIC) {
        val dir = ctx.getExternalFilesDir(null) ?: return
        if (running) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status(dir, "ERREUR autorisation du micro absente"); return
        }
        running = true
        thread = Thread({ record(dir, source) }, "essai-micro").also { it.start() }
    }

    /** Arrête l'enregistrement et termine le fichier. */
    fun stop() {
        running = false
        runCatching { thread?.join(3000) }
        thread = null
    }

    @Suppress("MissingPermission")
    private fun record(dir: File, source: Int) {
        var found: AudioRecord? = null
        var rate = 0
        for (r in RATES) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val a = runCatching { AudioRecord(source, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }.getOrNull()
            if (a != null && a.state == AudioRecord.STATE_INITIALIZED) { found = a; rate = r; break }
            runCatching { a?.release() }
        }
        val rec = found
        if (rec == null) { status(dir, "ERREUR micro refusé par l'autoradio (source $source)"); running = false; return }
        var bytes = 0
        var peak = 0
        var sum = 0L
        var samples = 0L
        var error: String? = null
        try {
            RandomAccessFile(File(dir, WAV), "rw").use { f ->
                f.setLength(0)
                f.write(wavHeader(rate, 0))
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) error = "le micro ne démarre pas (déjà pris ?)"
                status(dir, "ENREGISTRE source=$source frequence=$rate")
                val buf = ShortArray(rate / 4)
                val out = ByteArray(buf.size * 2)
                while (running && error == null && bytes < MAX_SECONDS * rate * 2) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) { error = "lecture du micro impossible ($n)"; break }
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte()
                        val a = abs(s); if (a > peak) peak = a; sum += a
                    }
                    f.write(out, 0, n * 2); bytes += n * 2; samples += n
                }
                f.seek(0); f.write(wavHeader(rate, bytes))
            }
        } catch (e: Exception) {
            error = e.toString()
        } finally {
            runCatching { rec.stop() }; runCatching { rec.release() }
            running = false
        }
        val avg = if (samples > 0) sum / samples else 0
        status(dir, (if (error != null) "ERREUR $error ; " else "FINI ") +
            "secondes=${bytes / 2 / rate.coerceAtLeast(1)} frequence=$rate source=$source niveau_moyen=$avg niveau_max=$peak")
    }
}

/**
 * Commandes de l'essai du micro, réservées au PC en ADB : le manifeste exige l'autorisation DUMP, que les applis
 * ordinaires n'ont pas. `am broadcast -n fr.p308.lanceur/.sound.MicTestReceiver -a fr.p308.lanceur.MIC_START`.
 */
class MicTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "fr.p308.lanceur.MIC_START" ->
                MicTest.start(ctx.applicationContext, intent.getIntExtra("source", MediaRecorder.AudioSource.MIC))
            "fr.p308.lanceur.MIC_STOP" -> { val r = goAsync(); Thread { MicTest.stop(); r.finish() }.start() }
        }
    }
}
