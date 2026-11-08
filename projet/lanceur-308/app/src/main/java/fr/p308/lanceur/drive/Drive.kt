package fr.p308.lanceur.drive

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

/** Vitesse du véhicule : l'OBD (plus rapide et précis) a priorité sur le GPS quand il répond. */
object Speed {
    private var lastObdNanos = 0L
    private var lastReportNanos = 0L

    @Synchronized
    fun report(kmh: Float, fromObd: Boolean, nanos: Long = System.nanoTime()) {
        val clean = if (kmh < 0.5f) 0f else kmh
        if (fromObd) lastObdNanos = nanos
        else if (lastObdNanos != 0L && nanos - lastObdNanos < 3_000_000_000L) return
        lastReportNanos = nanos
        Trip.feed(clean, nanos)
        Chrono.feed(clean, nanos)
    }

    /** Remet la vitesse courante à 0 si ni l'OBD ni le GPS n'ont envoyé de mesure depuis 3,5 s (arrêt / garage). */
    @Synchronized
    fun decayIfStale(nanos: Long = System.nanoTime()) {
        if (lastReportNanos != 0L && nanos - lastReportNanos > 3_500_000_000L && Trip.state.value.currentKmh > 0f) {
            lastReportNanos = nanos
            Trip.feed(0f, nanos)
            Chrono.feed(0f, nanos)
        }
    }
}

data class TripState(
    val startMillis: Long = System.currentTimeMillis(),
    val distanceM: Double = 0.0,
    val movingS: Double = 0.0,
    val maxKmh: Float = 0f,
    val currentKmh: Float = 0f,
) {
    val avgKmh: Double get() = if (movingS > 1) distanceM / movingS * 3.6 else 0.0
}

/** Trajet depuis le démarrage de l'autoradio : distance intégrée à partir de la vitesse. */
object Trip {
    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()
    private var lastNanos = 0L
    private var lastKmh = 0f

    @Synchronized
    fun feed(kmh: Float, nanos: Long) {
        if (kmh == 0f && lastKmh == 0f && _state.value.currentKmh == 0f) {
            lastNanos = nanos
            return
        }
        val dt = if (lastNanos == 0L) 0.0 else ((nanos - lastNanos) / 1e9).coerceIn(0.0, 5.0)
        val avg = (kmh + lastKmh) / 2.0
        lastNanos = nanos
        lastKmh = kmh
        _state.update { s ->
            s.copy(
                distanceM = s.distanceM + avg / 3.6 * dt,
                movingS = s.movingS + if (avg > 2) dt else 0.0,
                maxKmh = max(s.maxKmh, kmh),
                currentKmh = kmh,
            )
        }
    }

    @Synchronized
    fun reset() {
        lastNanos = 0L
        lastKmh = 0f
        _state.value = TripState()
    }
}

/** [minS] : temps en dessous duquel la mesure est forcément fausse (bien plus rapide qu'une supercar). */
enum class ChronoMode(val label: String, val from: Float, val to: Float, val minS: Float) {
    ZERO_100("0 → 100 km/h", 0f, 100f, 2.5f),
    R80_120("80 → 120 km/h", 80f, 120f, 1.5f),
}

/** Accélération maximale crédible : 45 km/h par seconde (environ 1,3 g). Au-delà, la vitesse a « sauté ». */
private const val MAX_KMH_PER_S = 45f

enum class ChronoPhase { WAITING, READY, RUNNING, DONE }

data class ChronoState(
    val mode: ChronoMode = ChronoMode.ZERO_100,
    val phase: ChronoPhase = ChronoPhase.WAITING,
    val elapsedS: Float = 0f,
    val resultS: Float? = null,
    val best: Map<ChronoMode, Float> = emptyMap(),
)

/** Chrono d'accélération : le départ et l'arrivée sont interpolés entre deux mesures de vitesse. */
object Chrono {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(ChronoState())
    val state: StateFlow<ChronoState> = _state.asStateFlow()
    private var prevKmh = -1f
    private var prevNanos = 0L
    private var startNanos = 0L

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("chrono", Context.MODE_PRIVATE)
        val best = ChronoMode.entries.mapNotNull { m -> prefs.getFloat(m.name, 0f).takeIf { it >= m.minS }?.let { m to it } }.toMap()
        _state.update { it.copy(best = best) }
    }

    fun select(mode: ChronoMode) = _state.update { it.copy(mode = mode, phase = ChronoPhase.WAITING, elapsedS = 0f, resultS = null) }

    fun resetBest() {
        prefs.edit().clear().apply()
        _state.update { it.copy(best = emptyMap()) }
    }

    /** Instant (ns) où la vitesse a franchi [target] entre la mesure précédente et celle-ci. */
    private fun crossing(target: Float, kmh: Float, nanos: Long): Long {
        if (prevKmh < 0f || kmh == prevKmh) return nanos
        val f = ((target - prevKmh) / (kmh - prevKmh)).coerceIn(0f, 1f)
        return prevNanos + ((nanos - prevNanos) * f).toLong()
    }

    /**
     * Vrai si la vitesse a augmenté plus vite qu'une voiture ne le peut depuis la mesure précédente
     * (saut du GPS, mesure manquée, mode démo) : la mesure en cours n'est alors pas fiable.
     */
    private fun jumped(kmh: Float, nanos: Long): Boolean {
        if (prevKmh < 0f || kmh <= prevKmh) return false
        val dt = (nanos - prevNanos) / 1e9f
        if (dt <= 0f) return true
        return (kmh - prevKmh) / dt > MAX_KMH_PER_S && kmh - prevKmh > 10f
    }

    @Synchronized
    fun feed(kmh: Float, nanos: Long) {
        val s = _state.value
        val m = s.mode
        if (jumped(kmh, nanos) && (s.phase == ChronoPhase.READY || s.phase == ChronoPhase.RUNNING)) {
            // Départ ou chrono faussé : on annule et on attend de pouvoir réarmer proprement.
            _state.update { it.copy(phase = ChronoPhase.WAITING, elapsedS = 0f) }
            prevKmh = kmh
            prevNanos = nanos
            return
        }
        when (s.phase) {
            ChronoPhase.WAITING, ChronoPhase.DONE -> {
                val armed = if (m == ChronoMode.ZERO_100) kmh < 1f else kmh in 40f..(m.from - 2f)
                if (armed) _state.update { it.copy(phase = ChronoPhase.READY, elapsedS = 0f) }
            }
            ChronoPhase.READY -> when {
                m == ChronoMode.ZERO_100 && kmh >= 1f -> start(if (prevKmh in 0f..1f) prevNanos else nanos)
                m == ChronoMode.R80_120 && kmh >= m.from -> start(crossing(m.from, kmh, nanos))
                m == ChronoMode.R80_120 && kmh < 40f -> _state.update { it.copy(phase = ChronoPhase.WAITING) }
            }
            ChronoPhase.RUNNING -> {
                val elapsed = (nanos - startNanos) / 1e9f
                when {
                    kmh >= m.to -> finish((crossing(m.to, kmh, nanos) - startNanos) / 1e9f)
                    kmh < m.from - 5f || elapsed > 60f -> _state.update { it.copy(phase = ChronoPhase.WAITING, elapsedS = 0f) }
                    else -> _state.update { it.copy(elapsedS = elapsed) }
                }
            }
        }
        prevKmh = kmh
        prevNanos = nanos
    }

    private fun start(at: Long) {
        startNanos = at
        _state.update { it.copy(phase = ChronoPhase.RUNNING, elapsedS = 0f, resultS = null) }
    }

    private fun finish(seconds: Float) {
        if (seconds < _state.value.mode.minS) {
            // Temps impossible : jamais affiché comme résultat ni gardé comme record.
            _state.update { it.copy(phase = ChronoPhase.WAITING, elapsedS = 0f, resultS = null) }
            return
        }
        _state.update { s ->
            val best = s.best[s.mode]
            val newBest = if (best == null || seconds < best) seconds.also { prefs.edit().putFloat(s.mode.name, it).apply() } else best
            s.copy(phase = ChronoPhase.DONE, elapsedS = seconds, resultS = seconds, best = s.best + (s.mode to newBest))
        }
    }
}
