package fr.p308.lanceur.vehicle

import android.content.Context
import android.location.GnssStatus
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GpsState(
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val speedKmh: Float? = null,
    val fixAgeS: Float? = null,
    val satsUsed: Int? = null,
    val satsVisible: Int? = null,
    val frozen: Boolean = false,
    val freezes: Int = 0,
    val lastFreeze: String? = null,
    /** Gels signalés à la main (Waze/Plans figé dans CarPlay). */
    val carplayMarks: Int = 0,
)

/**
 * Surveille le GPS de l'autoradio et enregistre un journal CSV par jour (gps/AAAA-MM-JJ.csv).
 * Les écritures disque sont mises en mémoire tampon et vidées en arrière-plan (Dispatchers.IO)
 * toutes les 15 s pour ne jamais bloquer le fil principal (interface et 3D fluides).
 */
object GpsMonitor {
    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state.asStateFlow()
    lateinit var dir: File
        private set
    private var lastFixMillis = 0L
    private var lastLoc: Location? = null
    private var sameCount = 0
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingLines = ArrayList<String>(32)
    private var flushStarted = false

    fun init(ctx: Context) {
        ioScope.launch {
            dir = File(ctx.getExternalFilesDir(null), "gps").apply { mkdirs() }
        }
        synchronized(this) {
            if (!flushStarted) {
                flushStarted = true
                ioScope.launch {
                    while (isActive) {
                        delay(15_000)
                        flushBuffer()
                    }
                }
            }
        }
    }

    @Synchronized
    fun onLocation(loc: Location) {
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else null
        val same = lastLoc?.let { it.latitude == loc.latitude && it.longitude == loc.longitude } ?: false
        sameCount = if (same && (kmh ?: _state.value.speedKmh ?: 0f) > 10f) sameCount + 1 else 0
        lastFixMillis = System.currentTimeMillis()
        lastLoc = loc
        _state.update {
            it.copy(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                speedKmh = kmh,
                fixAgeS = 0f,
            )
        }
        when {
            sameCount >= 3 -> freeze("position identique 3 fois à ${kmh?.toInt()} km/h")
            _state.value.frozen && !same -> {
                _state.update { it.copy(frozen = false) }
                log("REPRISE", "", immediate = true)
            }
            else -> log("POSITION", "", immediate = false)
        }
    }

    fun onGnss(status: GnssStatus) {
        var used = 0
        for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
        val cur = _state.value
        if (cur.satsUsed != used || cur.satsVisible != status.satelliteCount) {
            _state.update { it.copy(satsUsed = used, satsVisible = status.satelliteCount) }
        }
    }

    /** Le passager signale que la position s'est figée dans CarPlay : on le note immédiatement. */
    @Synchronized
    fun markCarPlayFreeze() {
        _state.update { it.copy(carplayMarks = it.carplayMarks + 1) }
        log("SIGNALEMENT_CARPLAY", "position figée dans Waze/Plans (signalé à la main)", immediate = true)
    }

    /** Appelé chaque seconde par le service. */
    @Synchronized
    fun tick() {
        if (lastFixMillis == 0L) return
        val age = (System.currentTimeMillis() - lastFixMillis) / 1000f
        val prevInt = _state.value.fixAgeS?.toInt() ?: -1
        if (age.toInt() != prevInt) {
            _state.update { it.copy(fixAgeS = age) }
        }
        if (age > 5f && (_state.value.speedKmh ?: 0f) > 10f && !_state.value.frozen) {
            freeze("plus de position depuis ${age.toInt()} s")
        }
    }

    private fun freeze(reason: String) {
        _state.update { it.copy(frozen = true, freezes = it.freezes + 1, lastFreeze = "${LocalDateTime.now().format(time)} — $reason") }
        log("GEL", reason, immediate = true)
    }

    private fun log(kind: String, detail: String, immediate: Boolean) {
        val s = _state.value
        val line = listOf(
            LocalDateTime.now().toString(), kind,
            s.lat?.let { "%.6f".format(Locale.US, it) } ?: "", s.lon?.let { "%.6f".format(Locale.US, it) } ?: "",
            s.accuracyM?.toInt() ?: "", s.speedKmh?.toInt() ?: "", s.satsUsed ?: "", s.satsVisible ?: "", detail,
        ).joinToString(";")
        synchronized(pendingLines) {
            if (pendingLines.size < 500) pendingLines.add(line)
        }
        if (immediate) ioScope.launch { flushBuffer() }
    }

    private fun flushBuffer() {
        if (!::dir.isInitialized) return
        val batch: List<String>
        synchronized(pendingLines) {
            if (pendingLines.isEmpty()) return
            batch = ArrayList(pendingLines)
            pendingLines.clear()
        }
        runCatching {
            val f = File(dir, "${LocalDate.now()}.csv")
            if (!f.exists()) f.writeText("heure;evenement;lat;lon;precision_m;vitesse_kmh;sat_utilises;sat_visibles;detail\n")
            f.appendText(batch.joinToString("\n", postfix = "\n"))
        }
    }
}
