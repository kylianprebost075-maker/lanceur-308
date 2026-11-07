package fr.p308.lanceur.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class BatteryLevel { UNKNOWN, OK, LOW, CRITICAL, NOT_CHARGING }

data class BatteryState(val volts: Float? = null, val level: BatteryLevel = BatteryLevel.UNKNOWN) {
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
 * Surveille la tension (CAN, sinon OBD). Moteur tournant si l'OBD donne un régime, sinon si la tension
 * dépasse 13,2 V (alternateur). Un niveau anormal doit durer 6 s (chute au démarreur ignorée) ;
 * l'alerte ne retombe qu'après 10 s de tension normale (pas de clignotement).
 */
object BatteryWatch {
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()
    private var candidate = BatteryLevel.UNKNOWN
    private var since = 0L

    fun update(volts: Float?, rpm: Int?, nowMs: Long = System.currentTimeMillis()): BatteryState {
        if (volts == null) return BatteryState().also { _state.value = it }
        val running = if (rpm != null) rpm > 400 else volts >= 13.2f
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
        return BatteryState(volts, level).also { _state.value = it }
    }
}
