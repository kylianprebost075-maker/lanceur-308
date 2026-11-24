package fr.p308.lanceur.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/** État de l'attente au démarrage, lu par l'accueil, le panneau à côté de CarPlay et le service (bandeau, voix). */
data class TurboStartState(
    /** Secondes à attendre encore avant de partir (0 = pas d'attente en cours). */
    val waitS: Int = 0,
    /** Durée totale de l'attente en cours. */
    val totalS: Int = 0,
    /** Vrai quelques secondes après la fin de l'attente : « tu peux partir ». */
    val ready: Boolean = false,
)

/**
 * Attente au démarrage pour le graissage du turbo (v0.47, demandée par l'auteur). Moteur coupé un moment, l'huile est
 * redescendue dans le carter : au démarrage, il lui faut quelques secondes pour remonter jusqu'aux paliers du turbo,
 * qui tournent très vite. Partir et accélérer tout de suite les fait tourner mal graissés (usure, jeu, fuite d'huile).
 * Le Lanceur affiche donc un compte à rebours au démarrage (30 s par défaut, réglable ; 15 s de plus quand il gèle,
 * l'huile froide étant plus épaisse), puis « tu peux partir ». Ensuite, rouler doucement tant que le moteur est froid.
 *
 * Pas d'attente après un arrêt de moins de 5 min (l'huile n'a pas eu le temps de redescendre) ni si la voiture roule
 * déjà. Si elle part avant la fin, le compte s'arrête et c'est noté au journal. Logique pure : TurboStartTest.
 */
object TurboStart {
    /** Arrêt plus court : pas d'attente au redémarrage. */
    const val QUICK_RESTART_MS = 5 * 60_000L
    /** Le moteur n'est jugé arrêté qu'après ce délai sans signe de marche (tension qui oscille au démarrage). */
    const val OFF_CONFIRM_MS = 20_000L
    /** Durée d'affichage de « tu peux partir ». */
    const val READY_SHOW_MS = 8_000L
    /** Secondes en plus quand il gèle dehors. */
    const val COLD_EXTRA_S = 15
    /** Vitesse au-delà de laquelle la voiture est partie. */
    private const val MOVING_KMH = 5f

    enum class Kind { STARTED, READY, LEFT_EARLY }

    /** Ce qui vient de se passer : [seconds] = durée conseillée (STARTED), ou secondes attendues (LEFT_EARLY). */
    data class Event(val kind: Kind, val seconds: Int, val totalS: Int)

    private val _state = MutableStateFlow(TurboStartState())
    val state: StateFlow<TurboStartState> = _state.asStateFlow()

    /** Plus de 30 s sans appel (une par seconde normalement) : l'autoradio dormait, contact coupé. */
    const val SLEEP_GAP_MS = 30_000L
    /** Après le réveil, signes de marche ignorés le temps que la tension et le régime soient relus. */
    const val WAKE_GRACE_MS = 4_000L

    private var running = false
    private var lastTickMs = 0L
    private var wakeUntilMs = 0L
    private var lastRunMs = 0L
    private var stoppedAtMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var totalS = 0
    private var readyAtMs = 0L
    private var preview = false

    /** Durée de l'attente : réglage + [COLD_EXTRA_S] s s'il gèle dehors. */
    fun duration(baseS: Int, outsideC: Float?): Int = baseS + if (outsideC != null && outsideC <= 0f) COLD_EXTRA_S else 0

    /** Montre l'attente tout de suite (bouton « Voir » des Réglages), sans moteur ; rien n'est noté au journal. */
    @Synchronized
    fun preview(baseS: Int, outsideC: Float?, nowMs: Long = System.currentTimeMillis()) {
        preview = true
        totalS = duration(baseS, outsideC)
        startMs = nowMs
        endMs = nowMs + totalS * 1000L
        readyAtMs = 0L
        _state.value = TurboStartState(totalS, totalS, false)
    }

    /**
     * Chaque seconde (service) : [engineSign] = signe de marche du moteur à cet instant (régime OBD, ou alternateur
     * qui charge vu par le CAN), [kmh] = vitesse. Renvoie l'événement à annoncer, ou null.
     */
    @Synchronized
    fun tick(engineSign: Boolean, kmh: Float, enabled: Boolean, baseS: Int, outsideC: Float?,
             nowMs: Long = System.currentTimeMillis()): Event? {
        var event: Event? = null
        // v0.50 : l'autoradio s'endort quelques secondes après la coupure du contact, avant les 20 s qui confirment
        // l'arrêt du moteur. Au réveil, le Lanceur le croyait donc toujours tournant et n'affichait rien : du 19 au
        // 24/11, attente montrée 2 fois sur 17 démarrages (seulement quand le démarreur venait 45 s après le contact).
        // Un trou de plus de [SLEEP_GAP_MS] entre deux secondes = autoradio endormi = moteur coupé pendant ce temps.
        if (lastTickMs != 0L && nowMs - lastTickMs > SLEEP_GAP_MS) {
            if (running) { running = false; stoppedAtMs = lastRunMs }
            if (!preview) { endMs = 0L; readyAtMs = 0L }
            // Au réveil, la tension et le régime gardés d'avant la veille sont périmés : on attend des valeurs neuves.
            wakeUntilMs = nowMs + WAKE_GRACE_MS
        }
        lastTickMs = nowMs
        val sign = engineSign && nowMs >= wakeUntilMs
        // Marche / arrêt du moteur, avec un délai avant de le croire arrêté.
        if (sign) {
            lastRunMs = nowMs
            if (!running) {
                running = true
                val quickRestart = stoppedAtMs != 0L && nowMs - stoppedAtMs < QUICK_RESTART_MS
                if (enabled && kmh < MOVING_KMH && !quickRestart && !preview) {
                    totalS = duration(baseS, outsideC)
                    startMs = nowMs
                    endMs = nowMs + totalS * 1000L
                    readyAtMs = 0L
                    event = Event(Kind.STARTED, totalS, totalS)
                }
            }
        } else if (running && nowMs - lastRunMs >= OFF_CONFIRM_MS) {
            running = false
            stoppedAtMs = lastRunMs
            if (!preview) { endMs = 0L; readyAtMs = 0L }
        }
        if (!enabled && !preview) endMs = 0L
        if (endMs != 0L) {
            when {
                kmh >= MOVING_KMH -> {
                    if (!preview) event = Event(Kind.LEFT_EARLY, ((nowMs - startMs) / 1000).toInt(), totalS)
                    endMs = 0L
                    preview = false
                }
                nowMs >= endMs -> {
                    endMs = 0L
                    readyAtMs = nowMs
                    if (!preview) event = Event(Kind.READY, totalS, totalS)
                    preview = false
                }
            }
        }
        val waitS = if (endMs != 0L) ceil((endMs - nowMs) / 1000.0).toInt().coerceAtLeast(1) else 0
        val ready = readyAtMs != 0L && nowMs - readyAtMs < READY_SHOW_MS && kmh < MOVING_KMH
        _state.value = TurboStartState(waitS, if (waitS > 0 || ready) totalS else 0, ready)
        return event
    }

    /** Oublie tout (tests, restauration). */
    @Synchronized
    fun reset() {
        running = false; lastTickMs = 0L; wakeUntilMs = 0L; lastRunMs = 0L; stoppedAtMs = 0L
        startMs = 0L; endMs = 0L; totalS = 0; readyAtMs = 0L; preview = false
        _state.value = TurboStartState()
    }
}
