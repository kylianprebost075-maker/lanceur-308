package fr.p308.lanceur.vehicle

import android.content.Context

/**
 * Données véhicule des autoradios FYT via le service com.syu.ms (boîtier Raise PSA-RZ-15-0127-B1) :
 * - Module 7 (FinalCanbus) : ouvrants (0..5) et autonomie carburant (103).
 * - Module 0 (FinalMain)   : marche arrière (12 = U_BACKCAR) et veilleuses éventuelles (4 = U_LAMPLET).
 * - Température extérieure : canal appris par OutsideTemp (Réglages), dans l'un des deux modules.
 * Sur 308 T7 2007 avec faisceau PSA-RZ-15-0127-B1, le commodo de phares n'est pas câblé vers le boîtier CAN :
 * l'application gère donc les phares automatiquement avec le coucher du soleil (Mode Auto) + bouton Phares.
 */
object CanBusSource {
    private const val MODULE_MAIN = 0
    private const val MODULE_CANBUS = 7
    private const val MODULE_SOUND = 4
    private const val U_SOUND_VOL = 2

    private const val U_LAMPLET = 4
    private const val U_BACKCAR = 12
    private const val U_DRIVING_MILEAGE = 103

    private val DOORS = mapOf(
        0 to Opening.HOOD, 1 to Opening.FRONT_LEFT, 2 to Opening.FRONT_RIGHT,
        3 to Opening.REAR_LEFT, 4 to Opening.REAR_RIGHT, 5 to Opening.TRUNK,
    )

    private var bridge: FytBridge? = null
    private var everSeenCanLightOn = false

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (bridge != null) return
        everSeenCanLightOn = false

        // v0.17 : tous les canaux des deux modules, pour trouver et suivre la température extérieure (OutsideTemp).
        val mainCodes = 0..300
        val canCodes = 0..300

        bridge = FytBridge(onUpdate = { module, code, ints, _, _ ->
            // v0.30 : volume de l'autoradio (module son 4, code 2), pour les basses selon le volume.
            if (module == MODULE_SOUND && code == U_SOUND_VOL) { ints?.firstOrNull()?.let(fr.p308.lanceur.sound.BassAuto::onFytVolume); return@FytBridge }
            val temp = ints?.let { OutsideTemp.onValue(module, code, it) }
            if (Vehicle.demo) return@FytBridge
            if (temp != null && (Vehicle.state.value.outsideTempC != temp || Vehicle.state.value.outsideTempEstimated)) {
                Vehicle.update { s -> s.copy(outsideTempC = temp, outsideTempEstimated = false) }
            }
            val v = ints?.firstOrNull() ?: return@FytBridge

            when (module) {
                MODULE_CANBUS -> {
                    DOORS[code]?.let { opening ->
                        val isOpen = v != 0
                        if ((opening in Vehicle.state.value.open) != isOpen) {
                            Vehicle.update { s -> s.copy(open = if (isOpen) s.open + opening else s.open - opening, source = "CAN (FYT)") }
                        }
                    }
                    if (code == U_DRIVING_MILEAGE && v in 15..1999 && Vehicle.state.value.fuelRangeKm != v) {
                        Vehicle.update { s -> s.copy(fuelRangeKm = v, source = "CAN (FYT)") }
                    }
                }
                MODULE_MAIN -> when (code) {
                    U_BACKCAR -> {
                        val rev = v != 0
                        if (Vehicle.state.value.reverse != rev) {
                            Vehicle.update { s -> s.copy(reverse = rev, source = "CAN (FYT)") }
                        }
                    }
                    U_LAMPLET -> {
                        if (v != 0) {
                            everSeenCanLightOn = true
                            if (Vehicle.state.value.headlights != true) {
                                Vehicle.update { s -> s.copy(headlights = true, source = "CAN (FYT)") }
                            }
                        } else if (everSeenCanLightOn && Vehicle.state.value.headlights != false) {
                            Vehicle.update { s -> s.copy(headlights = false, source = "CAN (FYT)") }
                        }
                    }
                }
            }
        }, log = {}).also {
            it.connect(app, mapOf(MODULE_MAIN to mainCodes, MODULE_CANBUS to canCodes, MODULE_SOUND to listOf(U_SOUND_VOL)))
        }
    }

    fun restart(ctx: Context) {
        bridge?.disconnect(ctx)
        bridge = null
        start(ctx)
    }

    fun stop(ctx: Context) {
        bridge?.disconnect(ctx)
        bridge = null
    }
}
