package fr.p308.lanceur.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fr.p308.lanceur.vehicle.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Annonces vocales des alertes (synthèse vocale d'Android, en français). La musique baisse le temps de la phrase
 * puis remonte. Jamais en mode démo, sauf le bouton « Essayer ». Au plus une annonce identique par minute.
 */
object Voice {
    enum class Status { OFF, STARTING, READY, NO_FRENCH }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status

    private var tts: TextToSpeech? = null
    private var am: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private val lastSaid = HashMap<String, Long>()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun init(ctx: Context) {
        if (tts != null) return
        val app = ctx.applicationContext
        am = app.getSystemService(AudioManager::class.java)
        _status.value = Status.STARTING
        tts = TextToSpeech(app) { st ->
            val t = tts
            if (st != TextToSpeech.SUCCESS || t == null) {
                _status.value = Status.NO_FRENCH
                return@TextToSpeech
            }
            val ok = t.setLanguage(Locale.FRANCE)
            _status.value = if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) Status.NO_FRENCH else Status.READY
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = release()
                @Deprecated("Ancienne API")
                override fun onError(id: String?) = release()
            })
        }
    }

    private fun release() {
        focus?.let { f -> runCatching { am?.abandonAudioFocusRequest(f) } }
        focus = null
    }

    /** Dit `text` si les annonces sont activées (ou si `force`, pour l'essai). */
    fun say(text: String, force: Boolean = false) {
        if (!force && (!SoundPrefs.voice || Vehicle.demo)) return
        val t = tts ?: return
        if (_status.value != Status.READY) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastSaid[text] ?: 0L) < 60_000L) return
        lastSaid[text] = now
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        focus = f
        runCatching { am?.requestAudioFocus(f) }
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "lanceur-$now")
    }
}
