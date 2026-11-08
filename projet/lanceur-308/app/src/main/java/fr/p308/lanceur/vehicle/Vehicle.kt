package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.p308.lanceur.drive.Speed
import fr.p308.lanceur.drive.Trip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Source unique de l'état du véhicule. Les données CAN (portes, radars, température…)
 * y seront poussées par CanBusSource une fois le protocole de l'autoradio identifié.
 */
object Vehicle {
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var demoJob: Job? = null
    private var pendingMeters = 0f

    var demo by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = ctx.getSharedPreferences("vehicle", Context.MODE_PRIVATE)
        publishGpsOdometer()
        updateDemo(prefs.getBoolean("demo", false))
    }

    fun update(f: (VehicleState) -> VehicleState) = _state.update(f)

    /** Kilométrage saisi à la main ; le GPS ajoute ensuite la distance parcourue. */
    fun setOdometer(km: Int) {
        // Une saisie à la main corrige tout, y compris un relevé moteur faux : la limite « ne recule pas » repart d'ici.
        prefs.edit().remove("ecuKm").apply()
        setBase(km, fromEcu = false)
    }

    /**
     * Kilométrage lu dans le calculateur moteur : il devient le nouveau point de départ et le GPS repart de zéro.
     * Si la lecture échoue au démarrage suivant, rien n'est perdu : le dernier relevé + les km du GPS restent affichés.
     * Une valeur plus petite que le dernier relevé moteur (le compteur ne recule jamais) est une lecture faussée :
     * refusée. Renvoie vrai si la valeur est acceptée.
     */
    fun setOdometerFromEcu(km: Int): Boolean {
        val lastEcu = prefs.getInt("ecuKm", -1)
        if (lastEcu > 0 && km < lastEcu - 1) return false
        prefs.edit().putInt("ecuKm", km).apply()
        setBase(km, fromEcu = true)
        return true
    }

    private fun setBase(km: Int, fromEcu: Boolean) {
        pendingMeters = 0f
        prefs.edit().putInt("baseKm", km).putLong("gpsMeters", 0L)
            .putLong("baseAt", System.currentTimeMillis()).putBoolean("baseEcu", fromEcu).apply()
        publishGpsOdometer()
    }

    /** À appeler après une restauration de sauvegarde. */
    fun reloadOdometer() = publishGpsOdometer()

    private var lastGpsMeterMs = 0L
    private var lastObdNanos = 0L

    fun addGpsMeters(m: Float) {
        lastGpsMeterMs = System.currentTimeMillis()
        addMeters(m)
    }

    /**
     * Secours quand le GPS ne compte plus (tunnel, parking couvert, pas de signal) : la vitesse lue sur l'OBD est
     * intégrée, mais seulement si le GPS n'a rien ajouté depuis 5 s (jamais les deux en même temps).
     */
    @Synchronized
    fun addObdSpeed(kmh: Int, nanos: Long = System.nanoTime()) {
        val prev = lastObdNanos
        lastObdNanos = nanos
        if (prev == 0L || kmh < 3 || demo) return
        val dt = ((nanos - prev) / 1e9).coerceIn(0.0, 3.0)
        if (System.currentTimeMillis() - lastGpsMeterMs < 5_000L) return
        addMeters((kmh / 3.6 * dt).toFloat())
    }

    @Synchronized
    private fun addMeters(m: Float) {
        if (_state.value.odometerFromCan) return
        pendingMeters += m
        if (pendingMeters < 50f) return
        val add = pendingMeters.toLong()
        pendingMeters -= add
        prefs.edit().putLong("gpsMeters", prefs.getLong("gpsMeters", 0L) + add).apply()
        publishGpsOdometer()
    }

    private fun publishGpsOdometer() {
        if (_state.value.odometerFromCan) return
        val base = prefs.getInt("baseKm", -1)
        val gpsKm = (prefs.getLong("gpsMeters", 0L) / 1000L).toInt()
        val km = if (base < 0) null else base + gpsKm
        _state.update {
            it.copy(
                odometerKm = km, odometerGpsKm = if (base < 0) 0 else gpsKm, odometerBaseKm = base.takeIf { b -> b >= 0 },
                odometerReadAtMs = prefs.getLong("baseAt", 0L).takeIf { t -> t > 0 && base >= 0 },
                odometerFromEcu = prefs.getBoolean("baseEcu", false),
            )
        }
    }

    /** Mode démo : simule roulage (roues + route animée), ouvrants, phares et radars pour tout tester à l'arrêt. */
    fun updateDemo(on: Boolean) {
        demo = on
        prefs.edit().putBoolean("demo", on).apply()
        // Les trajets de démo ne se mélangent jamais aux vrais : le trajet en cours est abandonné au changement.
        if (fr.p308.lanceur.drive.TripLog.demo != on) {
            fr.p308.lanceur.drive.TripLog.demo = on
            fr.p308.lanceur.drive.TripLog.discard()
        }
        Warmup.demo = on
        demoJob?.cancel()
        if (on) Engine.startDemo() else if (::appContext.isInitialized) Engine.stopDemo(appContext)
        if (!on) {
            Speed.report(0f, fromObd = true)
            Trip.reset()
            _state.update {
                it.copy(open = emptySet(), reverse = false, fogLights = false, headlights = null, rearRadarCm = emptyList(),
                    outsideTempC = null, outsideTempEstimated = false, batteryV = null, fuelRangeKm = null, source = "Aucune")
            }
            if (::appContext.isInitialized) CanBusSource.restart(appContext)
            return
        }
        demoJob = scope.launch {
            var tick = 0
            while (isActive) {
                val sec = (tick / 2) % 22
                when {
                    // 0..9 s : démonstration du roulage à 78 km/h avec phares allumés
                    sec < 10 -> {
                        val demoKmh = if (sec == 0) 42f else if (sec == 9) 35f else 78f
                        Speed.report(demoKmh, fromObd = true)
                        _state.update {
                            it.copy(
                                open = emptySet(),
                                headlights = true,
                                fogLights = false,
                                reverse = false,
                                rearRadarCm = emptyList(),
                                outsideTempC = 11.0f,
                                batteryV = 14.3f,
                                fuelRangeKm = 540,
                                source = "Démo (roulage)",
                            )
                        }
                    }
                    // 10..13 s : arrêt sur plateau studio + ouverture porte conducteur
                    sec < 14 -> {
                        Speed.report(0f, fromObd = true)
                        _state.update {
                            it.copy(
                                open = setOf(Opening.FRONT_LEFT),
                                headlights = false,
                                fogLights = false,
                                reverse = false,
                                rearRadarCm = emptyList(),
                                outsideTempC = 11.0f,
                                batteryV = 14.2f,
                                fuelRangeKm = 540,
                                source = "Démo",
                            )
                        }
                    }
                    // 14..17 s : coffre + marche arrière / radars
                    sec < 18 -> {
                        Speed.report(0f, fromObd = true)
                        _state.update {
                            it.copy(
                                open = setOf(Opening.TRUNK),
                                headlights = true,
                                fogLights = false,
                                reverse = true,
                                rearRadarCm = List(4) { i -> 35 + (tick * 13 + i * 35) % 120 },
                                outsideTempC = 11.0f,
                                batteryV = 14.2f,
                                fuelRangeKm = 540,
                                source = "Démo",
                            )
                        }
                    }
                    // 18..21 s : repos immobile en 3/4 studio avec phares + antibrouillards
                    else -> {
                        Speed.report(0f, fromObd = true)
                        _state.update {
                            it.copy(
                                open = emptySet(),
                                headlights = true,
                                fogLights = true,
                                reverse = false,
                                rearRadarCm = emptyList(),
                                outsideTempC = 11.0f,
                                batteryV = 14.2f,
                                fuelRangeKm = 540,
                                source = "Démo",
                            )
                        }
                    }
                }
                tick++
                delay(500)
            }
        }
    }
}
