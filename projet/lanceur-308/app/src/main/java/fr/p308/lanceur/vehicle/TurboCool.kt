package fr.p308.lanceur.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/**
 * Refroidissement du turbo. Après un effort (voie rapide, montée, accélérations franches), le turbo est très chaud ;
 * couper le moteur tout de suite arrête la circulation d'huile qui le refroidit et cuit l'huile dans ses paliers
 * (cause classique de turbo HS sur les HDi). Le Lanceur compte donc les secondes de ralenti conseillées avant
 * de couper : elles montent pendant l'effort (jusqu'à 60 s) et descendent en roulant doucement ou au ralenti.
 *
 * Repères : 5 min de voie rapide ≈ 60 s de ralenti conseillées ; une conduite en ville ne demande rien.
 */
data class TurboState(
    /** Secondes de ralenti conseillées avant de couper le moteur (0 = on peut couper). */
    val waitS: Int = 0,
    /** Vrai pendant 15 s quand l'attente vient de se terminer (« tu peux couper »). */
    val justCooled: Boolean = false,
    /** Vrai si le moteur tourne à l'arrêt (moment où le conseil est utile). */
    val stopped: Boolean = false,
)

object TurboCool {
    private const val MAX_S = 60.0
    private val _state = MutableStateFlow(TurboState())
    val state: StateFlow<TurboState> = _state.asStateFlow()

    private var need = 0.0
    private var cooledAtMs = 0L
    private var lastMs = 0L
    private var wasRunning = false

    /** Appelé chaque seconde par le service avec l'état moteur et la vitesse. */
    @Synchronized
    fun tick(e: EngineState, kmh: Float, nowMs: Long = System.currentTimeMillis()) {
        val dt = if (lastMs == 0L) 1.0 else ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 5.0)
        lastMs = nowMs
        val rpm = e.rpm ?: 0
        val running = rpm > 400
        val load = e.loadPct ?: 0
        val boost = e.boostBar ?: 0f
        val before = need
        when {
            !running -> {
                if (wasRunning && need >= 10) ObdLog.log("TURBO", "moteur coupé avec le turbo encore chaud (${ceil(need).toInt()} s conseillées)")
                // Moteur coupé : le turbo refroidit seul, mais lentement (pas de conseil à donner).
                need = (need - dt * 0.25).coerceAtLeast(0.0)
            }
            // Effort : fort couple ou forte pression de turbo, ou vitesse de voie rapide.
            (load >= 60 && rpm >= 2000) || boost >= 0.7f || kmh >= 100f -> need = (need + dt * 0.2).coerceAtMost(MAX_S)
            // Effort moyen : ne chauffe pas plus, ne refroidit pas non plus.
            load >= 40 || kmh >= 70f -> Unit
            // Ralenti à l'arrêt : c'est là que le turbo refroidit le mieux (huile qui circule, pas de charge).
            kmh < 2f && rpm < 1200 -> need = (need - dt).coerceAtLeast(0.0)
            // Conduite tranquille (ville, approche du parking) : refroidit aussi, un peu moins vite.
            else -> need = (need - dt * 0.6).coerceAtLeast(0.0)
        }
        wasRunning = running
        if (before >= 5 && need < 1 && running) cooledAtMs = nowMs
        _state.value = TurboState(
            waitS = if (need < 1) 0 else ceil(need).toInt(),
            justCooled = running && nowMs - cooledAtMs < 15_000L,
            stopped = running && kmh < 2f,
        )
    }
}
