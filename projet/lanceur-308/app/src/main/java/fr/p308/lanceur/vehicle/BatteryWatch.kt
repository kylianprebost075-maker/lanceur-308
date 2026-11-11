package fr.p308.lanceur.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class BatteryLevel { UNKNOWN, OK, LOW, CRITICAL, NOT_CHARGING }

data class BatteryState(
    val volts: Float? = null,
    val level: BatteryLevel = BatteryLevel.UNKNOWN,
    /** Minutes depuis que le moteur est coupé alors que l'autoradio est allumé (null = moteur tournant ou inconnu). */
    val engineOffMin: Int? = null,
) {
    val message: String?
        get() = volts?.let { v ->
            val vs = "%.1f V".format(Locale.FRANCE, v)
            when (level) {
                BatteryLevel.LOW -> "Batterie faible ($vs) : pense à la recharger"
                BatteryLevel.CRITICAL -> "Batterie très faible ($vs) : risque de non-démarrage"
                BatteryLevel.NOT_CHARGING -> "L'alternateur ne charge pas ($vs moteur tournant)"
                else -> null
            }
        }
}

/**
 * Surveille la tension (CAN de l'autoradio, sinon OBD) et le temps passé moteur coupé autoradio allumé. Moteur tournant si l'OBD donne un régime, sinon si la tension
 * dépasse 13,2 V (alternateur). Un niveau anormal doit durer 6 s (chute au démarreur ignorée) ;
 * l'alerte ne retombe qu'après 10 s de tension normale (pas de clignotement).
 */
object BatteryWatch {
    /** Rappel « moteur coupé, autoradio allumé » toutes les [DRAIN_STEP_MIN] minutes (v0.31). */
    const val DRAIN_STEP_MIN = 20

    /**
     * Tension lue par le boîtier CAN de l'autoradio (service FYT, module 0, code 114), v0.31. Relevé du 11/11 moteur
     * tournant : brut 142 → (142 + 4) × 0,1 = 14,6 V. Codage déduit de ce seul relevé : à confirmer moteur coupé
     * (12 à 13 V attendus). Une valeur hors de 6 à 17 V est ignorée.
     */
    const val FYT_MODULE = 0
    const val FYT_CODE = 114
    fun decodeFyt(raw: Int): Float? = ((raw + 4) / 10f).takeIf { it in 6f..17f }

    /** Palier du rappel (0 = rien, 1 = 20 min, 2 = 40 min…). */
    fun drainStep(engineOffMin: Int?): Int = (engineOffMin ?: 0) / DRAIN_STEP_MIN

    /** Phrase du rappel « moteur coupé », null avant le premier palier. */
    fun drainMessage(s: BatteryState): String? {
        val min = s.engineOffMin ?: return null
        if (drainStep(min) == 0) return null
        val vs = s.volts?.let { " (%.1f V)".format(Locale.FRANCE, it) } ?: ""
        return "Moteur coupé depuis $min min, autoradio allumé : la batterie se vide$vs. Démarre ou coupe le contact."
    }

    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()
    private var candidate = BatteryLevel.UNKNOWN
    private var since = 0L
    private var offSince: Long? = null

    fun update(volts: Float?, rpm: Int?, nowMs: Long = System.currentTimeMillis()): BatteryState {
        if (volts == null) { offSince = null; return BatteryState().also { _state.value = it } }
        val running = if (rpm != null) rpm > 400 else volts >= 13.2f
        offSince = if (running) null else offSince ?: nowMs
        val offMin = offSince?.let { ((nowMs - it) / 60_000).toInt() }
        val raw = when {
            running && rpm != null && volts < 12.8f -> BatteryLevel.NOT_CHARGING
            !running && volts < 11.8f -> BatteryLevel.CRITICAL
            !running && volts < 12.2f -> BatteryLevel.LOW
            else -> BatteryLevel.OK
        }
        if (raw != candidate) {
            candidate = raw
            since = nowMs
        }
        val current = _state.value.level
        val alerting = current != BatteryLevel.OK && current != BatteryLevel.UNKNOWN
        val confirmed = nowMs - since >= if (raw == BatteryLevel.OK) (if (alerting) 10_000 else 0) else 6_000
        val level = if (confirmed) raw else current.takeIf { it != BatteryLevel.UNKNOWN } ?: BatteryLevel.OK
        return BatteryState(volts, level, offMin).also { _state.value = it }
    }
}
