package fr.p308.lanceur.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class EngineState(
    val status: String = "Adaptateur OBD non connecté",
    val connected: Boolean = false,
    val rpm: Int? = null,
    val loadPct: Int? = null,
    val speedKmh: Int? = null,
    val coolantC: Int? = null,
    val boostBar: Float? = null,
    /** Tension mesurée par le calculateur (PID 42) ou par le boîtier Vgate (ATRV), utile pour l'alerte batterie. */
    val voltage: Float? = null,
    /** Débit d'air (PID 10, g/s) : sert à repérer les régénérations du FAP (vanne EGR fermée → plus d'air frais). */
    val mafGs: Float? = null,
    val torqueNm: Int? = null,
    val powerCh: Int? = null,
    val peakCh: Int = 0,
    /** Rapport estimé et conseil de passage (calculés, voir ShiftAdvice). */
    val advice: ShiftAdvice.Advice = ShiftAdvice.Advice(null, ShiftAdvice.Kind.NONE),
)

/** Résultat de la dernière lecture des codes défaut (onglet Moteur → Diagnostic). */
data class DiagState(
    val busy: Boolean = false,
    val status: String = "Pas encore lu",
    val readAtMs: Long? = null,
    val stored: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val permanent: List<String> = emptyList(),
    /** Kilométrage lu dans le calculateur moteur (EDC16C34, identifiant 21 10). */
    val ecuKm: Int? = null,
)

/** Codes défaut d'une réponse aux modes 03 / 07 / 0A (en-têtes masqués, trames « 0: … » comprises). */
internal fun parseDtcs(resp: String, modeByte: Int): List<String> {
    val hex = buildString {
        for (raw in resp.uppercase().split('\r', '\n')) {
            var t = raw.replace(" ", "").trim()
            if (t.isEmpty()) continue
            if (Regex("^[0-9A-F]:").containsMatchIn(t)) t = t.substring(2)
            else if (Regex("^[0-9A-F]{3}$").matches(t)) continue // longueur totale d'une réponse multi-trames
            if (!Regex("^[0-9A-F]+$").matches(t)) continue
            append(t)
        }
    }
    val bytes = hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
    val start = bytes.indexOf(modeByte)
    if (start < 0) return emptyList()
    val payload = bytes.drop(start)
    val pairs = if (payload.size % 2 == 0) payload.drop(2) else payload.drop(1)
    val codes = mutableListOf<String>()
    var i = 0
    while (i + 1 < pairs.size) {
        val a = pairs[i]
        val b = pairs[i + 1]
        if (a != 0 || b != 0) codes += "PCBU"[a shr 6] + "${(a shr 4) and 3}" + "%X".format(a and 0xF) + "%02X".format(b)
        i += 2
    }
    return codes.distinct()
}

/** Couple maxi selon le régime : courbe du profil de la voiture (CarProfile.kt). */
private val TORQUE_CURVE = CarProfile.TORQUE_CURVE

fun maxTorqueNm(rpm: Int): Float {
    if (rpm <= TORQUE_CURVE.first().first) return TORQUE_CURVE.first().second.toFloat()
    if (rpm >= TORQUE_CURVE.last().first) return TORQUE_CURVE.last().second.toFloat()
    val (r1, t1) = TORQUE_CURVE.last { it.first <= rpm }
    val (r2, t2) = TORQUE_CURVE.first { it.first > rpm }
    return t1 + (t2 - t1) * (rpm - r1).toFloat() / (r2 - r1)
}

/** Puissance estimée : charge calculée (PID 04) × couple maxi au régime courant. */
fun estimate(rpm: Int, loadPct: Int): Pair<Int, Int> {
    val torque = maxTorqueNm(rpm) * loadPct / 100f
    val kw = torque * rpm * 2 * PI / 60_000
    return torque.roundToInt() to (kw * 1.35962).roundToInt()
}

/**
 * Données moteur lues sur la prise OBD2 (optimisé pour Vgate vLinker MC+ Bluetooth, compatible iCar Pro 2S / ELM327) :
 * - Bluetooth Low Energy (« vLinker MC-IOS », service E7810A71) sans aucun appairage requis sur les autoradios FYT !
 * - Bluetooth classique SPP (« vLinker MC-Android », code 1234) s'il est appairé dans Android.
 */
object Engine {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _diag = MutableStateFlow(DiagState())
    val diag: StateFlow<DiagState> = _diag.asStateFlow()

    private enum class DiagRequest { READ, CLEAR }

    @Volatile
    private var diagRequest: DiagRequest? = null

    /**
     * Demande une lecture des codes défaut (et du kilométrage moteur), faite par la boucle OBD au prochain tour.
     * `clear = true` efface d'abord les codes (mode 04 normalisé), toujours après confirmation à l'écran.
     */
    fun requestDiag(clear: Boolean = false) {
        if (Vehicle.demo) {
            _diag.value = DiagState(status = "Démo : aucun code défaut", readAtMs = System.currentTimeMillis(), ecuKm = 140_721)
            return
        }
        if (!_state.value.connected) {
            _diag.update { it.copy(busy = false, status = "Boîtier OBD non connecté : branche le vLinker et mets le contact") }
            return
        }
        diagRequest = if (clear) DiagRequest.CLEAR else DiagRequest.READ
        _diag.update { it.copy(busy = true, status = if (clear) "Effacement des codes…" else "Lecture des codes…") }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    // Services UART BLE connus : Vgate vLinker MC+/FS/BM (E7810A71, 1 seule caractéristique bidirectionnelle),
    // Vgate iCar Pro / 2S (18F0, FFF0) et adaptateurs OBD BLE courants.
    private val VLINKER_SERVICE = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
    private val VLINKER_CHAR = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
    private val BLE_SERVICE_UUIDS = listOf(
        VLINKER_SERVICE,
        UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
    )
    private val VGATE_NOTIFY = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
    private val VGATE_WRITE = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")

    // « VLINK » couvre « vLinker MC-Android », « vLinker MC-IOS », « IOS-Vlink », « Android-Vlink ».
    private val NAMES = listOf(
        "VLINKER", "VLINK", "V-LINK", "VGATE", "ICAR",
        "OBD", "ELM", "LELINK", "KONNWEI", "VEEPEAK", "OBDLINK", "CARISTA",
    )

    @Volatile
    private var appCtx: Context? = null

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (job?.isActive == true) return
        job = scope.launch { connectLoop(ctx.applicationContext) }
    }

    /** Relance immédiatement la recherche Bluetooth / BLE (bouton « Rechercher OBD »). */
    fun reconnectNow(ctx: Context) {
        if (Vehicle.demo) return
        job?.cancel()
        job = null
        start(ctx)
    }

    fun resetPeak() = _state.update { it.copy(peakCh = 0) }

    fun startDemo() {
        job?.cancel()
        job = scope.launch {
            var t = 0.0
            while (isActive) {
                val c = t % 24.0
                val speed = when {
                    c < 3 -> 0.0
                    c < 15 -> 125 * (1 - kotlin.math.exp(-(c - 3) / 5.5)) / (1 - kotlin.math.exp(-12 / 5.5))
                    else -> 125 * (1 - (c - 15) / 9)
                }
                val accelerating = c in 3.0..15.0
                val rpm = if (speed < 1) 820 else (1300 + (speed % 45) * 60).roundToInt().coerceIn(900, 4400)
                val load = if (accelerating) 85 else if (speed < 1) 12 else 20
                publish(rpm, load, speed = speed.roundToInt(), coolant = 88, boost = load / 100f * 1.1f, voltage = 14.1f)
                fr.p308.lanceur.drive.Speed.report(speed.toFloat(), fromObd = true)
                _state.update { it.copy(status = "Démo", connected = true) }
                t += 0.2
                delay(200)
            }
        }
    }

    fun stopDemo(ctx: Context) {
        job?.cancel()
        job = null
        _state.value = EngineState(peakCh = _state.value.peakCh)
        start(ctx)
    }

    private fun publish(
        rpm: Int?, load: Int?, speed: Int? = null, coolant: Int? = null, boost: Float? = null, voltage: Float? = null,
        maf: Float? = null,
    ) {
        _state.update { s ->
            val (torque, power) = if (rpm != null && load != null) estimate(rpm, load) else (null to null)
            val r = rpm ?: s.rpm
            val l = load ?: s.loadPct
            val v = speed ?: s.speedKmh
            val c = coolant ?: s.coolantC
            s.copy(
                rpm = r, loadPct = l,
                speedKmh = v, coolantC = c, boostBar = boost ?: s.boostBar,
                voltage = voltage ?: s.voltage,
                mafGs = maf ?: s.mafGs,
                torqueNm = torque ?: s.torqueNm, powerCh = power ?: s.powerCh,
                peakCh = max(s.peakCh, power ?: 0),
                // « Moteur froid » : la plus basse de l'eau et de l'huile estimée (l'huile chauffe moins vite).
                advice = ShiftAdvice.advise(r, v, l, Warmup.coldTemp(c)),
            )
        }
    }

    private var lastLoggedStatus = ""
    private val statusLoggedAt = HashMap<String, Long>()

    private fun status(text: String, connected: Boolean = false) {
        // Journal : chaque changement d'état de la connexion, mais pas la ronde « Recherche… / Aucun boîtier »
        // qui tournait toutes les 14 s (27 min de lignes le 09/11) : un même message, hors connexion réussie,
        // n'est réécrit qu'au bout de 10 min.
        val now = System.currentTimeMillis()
        val recent = !connected && now - (statusLoggedAt[text] ?: 0L) < 10 * 60_000L
        if (text != lastLoggedStatus && !recent) {
            lastLoggedStatus = text
            statusLoggedAt[text] = now
            ObdLog.log("CONNEXION", text)
        }
        _state.update { it.copy(status = text, connected = connected) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vehicle", Context.MODE_PRIVATE)

    private suspend fun connectLoop(ctx: Context) {
        while (currentCoroutineContext().isActive) {
            try {
                val transport = openTransport(ctx)
                if (transport == null) {
                    delay(8_000)
                    continue
                }
                transport.use { session(it) }
            } catch (e: IOException) {
                status("OBD déconnecté (${e.message ?: "veille"}) — nouvel essai…")
            } catch (_: SecurityException) {
                status("Autorisation Bluetooth ou Localisation refusée")
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) return
                status("OBD : ${e.message ?: "erreur"} — nouvel essai…")
            }
            _state.update { EngineState(status = it.status, peakCh = it.peakCh) }
            diagRequest = null
            _diag.update { if (it.busy) it.copy(busy = false, status = "OBD déconnecté pendant la lecture : réessaie") else it }
            delay(6_000)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openTransport(ctx: Context): ElmTransport? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            status("Autorisation Bluetooth manquante")
            return null
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status("Bluetooth Android inactif sur l'autoradio")
            return null
        }

        // 1. Reconnexion BLE directe si on connaît déjà l'adresse MAC du Vgate iCar Pro 2S.
        val savedMac = prefs(ctx).getString("obdBleMac", null)
        if (!savedMac.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(savedMac)) {
            val savedDev = runCatching { adapter.getRemoteDevice(savedMac) }.getOrNull()
            if (savedDev != null) {
                status("Reconnexion BLE à ${savedDev.name ?: "boîtier OBD"}…")
                runCatching { BleTransport.connect(ctx, savedDev) }.getOrNull()?.let { return it }
                // Boîtier mémorisé absent (ex : ancien boîtier prêté) : on l'oublie pour ne plus perdre 5 s à chaque essai.
                prefs(ctx).edit().remove("obdBleMac").apply()
            }
        }

        // 2. Adaptateur déjà appairé dans Android (ex : Android-Vlink en SPP, ou bascule auto en BLE si SPP bloqué par FYT).
        val bonded = adapter.bondedDevices?.filter { d -> matchesObdName(d.name) }.orEmpty()
        for (dev in bonded) {
            if (dev.type != BluetoothDevice.DEVICE_TYPE_LE) {
                status("Connexion SPP à ${dev.name ?: dev.address}…")
                val spp = runCatching { SppTransport.connect(dev) }.getOrNull()
                if (spp != null) return spp
            }
            status("Connexion BLE à ${dev.name ?: dev.address}…")
            val ble = runCatching { BleTransport.connect(ctx, dev) }.getOrNull()
            if (ble != null) {
                prefs(ctx).edit().putString("obdBleMac", dev.address).apply()
                return ble
            }
        }

        // 3. Scan Bluetooth Low Energy automatique (capte « IOS-Vlink » du Vgate iCar Pro 2S sans aucun appairage !).
        status("Recherche vLinker / OBD (BLE)…")
        val scanned = scanBleAdapter(ctx, adapter, timeoutMs = 5_500L)
        if (scanned != null) {
            val label = scanned.second
            status("Connexion BLE à $label…")
            val ble = BleTransport.connect(ctx, scanned.first, labelOverride = label)
            prefs(ctx).edit().putString("obdBleMac", scanned.first.address).apply()
            return ble
        }

        status("Aucun boîtier OBD détecté (branche le vLinker et mets le contact)")
        return null
    }

    private fun matchesObdName(name: String?): Boolean =
        !name.isNullOrBlank() && NAMES.any { name.contains(it, ignoreCase = true) }

    @SuppressLint("MissingPermission")
    private suspend fun scanBleAdapter(ctx: Context, adapter: BluetoothAdapter, timeoutMs: Long): Pair<BluetoothDevice, String>? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = AtomicReference<Pair<BluetoothDevice, String>?>(null)

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found.get() != null) return
                val dev = result.device ?: return
                val rec = result.scanRecord
                val advName = rec?.deviceName?.takeIf { it.isNotBlank() } ?: dev.name
                val uuids = rec?.serviceUuids?.map { it.uuid }.orEmpty()
                val matchUuid = uuids.any { it in BLE_SERVICE_UUIDS }
                if (matchesObdName(advName) || matchUuid) {
                    found.compareAndSet(null, dev to (advName ?: "Vgate BLE"))
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val started = runCatching { scanner.startScan(null, settings, cb) }.isSuccess
        if (!started) return null
        try {
            var elapsed = 0L
            while (elapsed < timeoutMs && found.get() == null && currentCoroutineContext().isActive) {
                delay(150)
                elapsed += 150
            }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        return found.get()
    }

    private suspend fun session(elm: ElmTransport) {
        elm.cmd("ATZ", 3000)
        delay(300) // le vLinker MC+ redémarre sa puce après ATZ
        // ATAT1 = délais adaptatifs (plus de PID par seconde), ATST64 = 400 ms max d'attente calculateur.
        listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST64").forEach { elm.cmd(it, 1500) }
        // 308 T7 2007 : CAN ISO 15765-4 11 bits 500 kbit/s (protocole 6) → connexion immédiate,
        // sinon recherche automatique (ATSP0) pour tout autre véhicule.
        elm.cmd("ATSP6", 1500)
        // Kilométrage du calculateur moteur → kilométrage d'entretien. Demandé AVANT toute question OBD normale,
        // comme le fait l'outil PC qui le lit à coup sûr (v0.18) ; sinon nouveaux essais plus bas, après un silence.
        var kmRead = tryEcuKm(elm, quiet = false)
        if (elm.pid("00", 4000) == null) {
            elm.cmd("ATSP0", 1500)
            if (elm.pid("00", 8000) == null) throw IOException("calculateur muet (mets le contact)")
        }
        status("Connecté à ${elm.name}", connected = true)
        ObdLog.log("CONNEXION", "tension ${elm.readBatteryVolts() ?: "?"} V à la connexion")
        // Kilométrage pas lu à la connexion : 3 nouveaux essais au plus par connexion (chacun coupe la lecture
        // OBD une dizaine de secondes), au lieu d'un toutes les 5 min pendant tout le trajet.
        var kmTriedAt = System.currentTimeMillis()
        var kmRounds = 0
        val baro = elm.pid("33", minBytes = 1)?.firstOrNull() ?: 101
        CrankWatch.resetSession()
        var n = 0
        var engineOff = true
        while (currentCoroutineContext().isActive) {
            diagRequest?.let { req ->
                diagRequest = null
                runDiag(elm, req)
                if (_diag.value.ecuKm != null) kmRead = true
            }
            if (!kmRead && kmRounds < 3) {
                val wait = if (engineOff) 60_000L else 5 * 60_000L
                if (System.currentTimeMillis() - kmTriedAt > wait) {
                    kmRounds++
                    kmRead = tryEcuKm(elm)
                    kmTriedAt = System.currentTimeMillis()
                }
            }
            // minBytes : une réponse tronquée (bruit Bluetooth) est ignorée au lieu de couper la connexion.
            val rpm = elm.pid("0C", minBytes = 2)?.let { (it[0] * 256 + it[1]) / 4 }
            // Pendant le démarreur, le calculateur ne répond souvent plus : on reste en mode « moteur coupé ».
            if (rpm != null && (rpm <= 400) != engineOff) {
                ObdLog.log("MOTEUR", if (rpm > 400) "démarré ($rpm tr/min, eau ${_state.value.coolantC ?: "?"} °C)" else "coupé")
            }
            if (rpm != null) engineOff = rpm <= 400
            if (engineOff) {
                // Moteur coupé : régime + tension, plusieurs fois par seconde, pour saisir la chute au démarreur.
                // Deux lectures de tension par tour : le calculateur répond lentement (jusqu'à 400 ms) au démarreur,
                // ATRV (lu par le vLinker lui-même) reste rapide.
                val volts = elm.readBatteryVolts()
                CrankWatch.onSample(volts, rpm, _state.value.coolantC)?.let(::logCrank)
                // Moteur coupé : une tension par minute suffit au journal (les relevés rapides restent en mémoire).
                if (n % 120 == 0) ObdLog.log("TENSION", "moteur coupé : ${volts ?: "?"} V")
                val coolant = if (n % 20 == 0) elm.pid("05", minBytes = 1)?.let { it[0] - 40 } else null
                val volts2 = elm.readBatteryVolts()
                CrankWatch.onSample(volts2, null, _state.value.coolantC)?.let(::logCrank)
                publish(rpm, null, coolant = coolant, voltage = volts2 ?: volts)
                n++
                continue
            }
            CrankWatch.onSample(null, rpm, _state.value.coolantC)?.let(::logCrank)
            val load = elm.pid("04", minBytes = 1)?.let { it[0] * 100 / 255 }
            val speed = if (n % 2 == 0) elm.pid("0D", minBytes = 1)?.get(0) else null
            speed?.let {
                fr.p308.lanceur.drive.Speed.report(it.toFloat(), fromObd = true)
                Vehicle.addObdSpeed(it)
            }
            // Régime et vitesse du même tour : sert à apprendre l'étagement réel de la boîte.
            if (rpm != null && speed != null) GearCalib.feed(rpm, speed)
            if (n % 10 == 1) {
                val coolant = elm.pid("05", minBytes = 1)?.let { it[0] - 40 }
                val boost = elm.pid("0B", minBytes = 1)?.let { (it[0] - baro) / 100f }
                // Le PID 42 (tension) est absent sur l'EDC16C34 (séance du 08/11) : ATRV lit le 12 V du vLinker.
                val volts = elm.readBatteryVolts()
                CrankWatch.onRunningVolts(volts)
                if (n % 600 == 1) ObdLog.log("TENSION", "moteur tournant : ${volts ?: "?"} V, eau ${coolant ?: "?"} °C, turbo ${boost ?: "?"} bar")
                publish(rpm, load, speed, coolant, boost, volts)
            } else if (n % 2 == 1) {
                // Débit d'air un tour sur deux (≈ 1 fois par seconde) : détection des régénérations du FAP.
                val maf = elm.pid("10", minBytes = 2)?.let { (it[0] * 256 + it[1]) / 100f }
                publish(rpm, load, speed, maf = maf)
            } else {
                publish(rpm, load, speed)
            }
            n++
            // Mode CarPlay : ≈ 3 tours par seconde au lieu d'en continu, pour laisser la puce Bluetooth / Wi-Fi
            // (partagée avec le CarPlay sans fil) et le processeur à CarPlay. Moteur coupé : pas de pause (démarreur).
            if (fr.p308.lanceur.optim.Optim.lightNow()) delay(300)
        }
    }

    /** Lecture (et effacement si demandé) des codes défaut normalisés, puis du kilométrage moteur. */
    private fun runDiag(elm: ElmTransport, req: DiagRequest) {
        try {
            var clearMsg = ""
            if (req == DiagRequest.CLEAR) {
                val ok = elm.cmd("04", 5000).uppercase().filter { it.isLetterOrDigit() }.contains("44")
                clearMsg = if (ok) "Codes effacés. " else "Effacement refusé par le calculateur (coupe le moteur, contact mis). "
            }
            val stored = parseDtcs(elm.cmd("03", 4000), 0x43)
            val pending = parseDtcs(elm.cmd("07", 4000), 0x47)
            val permanent = parseDtcs(elm.cmd("0A", 4000), 0x4A)
            val km = runCatching { readEcuKm(elm) }.getOrNull()?.takeIf(::applyEcuKm)
            val total = (stored + pending + permanent).distinct().size
            _diag.value = DiagState(
                busy = false,
                status = clearMsg + if (total == 0) "Aucun code défaut" else "$total code(s) défaut",
                readAtMs = System.currentTimeMillis(),
                stored = stored, pending = pending, permanent = permanent,
                ecuKm = km ?: _diag.value.ecuKm,
            )
        } catch (e: IOException) {
            _diag.update { it.copy(busy = false, status = "Lecture interrompue (${e.message ?: "OBD"}) : réessaie") }
            throw e
        }
    }

    /**
     * Kilométrage du calculateur moteur PSA EDC16C34 : requête KWP 21 10 vers 6A8, réponse 688
     * « 61 10 00 02 25 B1 » = 140 721 km. Lecture seule ; l'adressage OBD normal est toujours rétabli.
     */
    private fun readEcuKm(elm: ElmTransport, quiet: Boolean = true): Int? {
        /** Réponse du boîtier sur une ligne, pour le journal. */
        fun shown(s: String) = s.trim().replace(Regex("[\\r\\n]+"), " | ").ifEmpty { "(vide)" }
        try {
            // v0.18 (séance du 09/11 avec l'outil PC, même boîtier) : le calculateur ouvre le dialogue avec « 81 »
            // (pas 10C0, restée sans réponse en v0.17) et donne alors 2110 moteur coupé comme moteur tournant.
            // Dans la voiture, la v0.17 n'obtenait même pas de réponse à 3E00 alors que l'outil PC en avait une :
            // seule différence, l'appli venait de poser des questions OBD normales (7DF). D'où deux façons :
            //  - quiet = false : tout de suite, AVANT toute question OBD (à la connexion, comme l'outil PC) ;
            //  - quiet = true : après un silence de 3 s puis de 6 s, le temps que le calculateur referme le dialogue OBD.
            val pauses = if (quiet) listOf(3_000L, 6_000L) else listOf(0L)
            val engine = if ((_state.value.rpm ?: 0) > 400) "moteur tournant" else "moteur coupé"
            listOf("ATSH6A8", "ATCRA688", "ATFCSH6A8", "ATFCSD300000", "ATFCSM1").forEach { elm.cmd(it, 1500) }
            pauses.forEachIndexed { k, pause ->
                if (pause > 0) Thread.sleep(pause)
                // Dernier essai : attente d'une seconde entière par réponse (ATSTFF) au lieu de 400 ms.
                if (quiet && k == pauses.lastIndex) listOf("ATAT0", "ATSTFF").forEach { elm.cmd(it, 1500) }
                val open = elm.cmd("81", 2500)
                val raw = elm.cmd("2110", 3000)
                val hex = raw.uppercase().filter { it.isLetterOrDigit() }
                val i = hex.indexOf("6110")
                if (i >= 0 && hex.length >= i + 12) {
                    hex.substring(i + 4, i + 12).toLongOrNull(16)?.toInt()?.takeIf { it in 1..1_999_999 }?.let {
                        ObdLog.log("KM", "lu : $it km (essai ${k + 1}/${pauses.size}, $engine, silence ${pause / 1000} s)")
                        return it
                    }
                }
                // Réponses brutes gardées : ce sont elles qui disent pourquoi ça échoue (NO DATA, 7F 21 xx, rien…).
                ObdLog.log(
                    "KM",
                    "essai ${k + 1}/${pauses.size} raté ($engine, silence ${pause / 1000} s) : " +
                        "81 → « ${shown(open)} », 2110 → « ${shown(raw)} »",
                )
            }
            return null
        } finally {
            // Fin du dialogue (82), puis retour à l'adressage OBD normal.
            runCatching { elm.cmd("82", 1500) }
            listOf("ATFCSM0", "ATCRA", "ATSH7DF", "ATST64", "ATAT1").forEach { runCatching { elm.cmd(it, 1500) } }
        }
    }

    /** Démarrage noté par CrankWatch → journal (avec ce qui a été saisi ou non). */
    private fun logCrank(r: CrankRecord) {
        ObdLog.log(
            "DEMARRAGE",
            "repos ${r.restV} V, " + (r.minV?.let { "mini $it V au démarreur" } ?: "chute au démarreur NON saisie") +
                ", eau ${r.coolantC ?: "?"} °C, verdict ${r.verdict.label}",
        )
    }

    /** Lit le kilométrage moteur ; vrai s'il a été obtenu. Une coupure Bluetooth remonte (IOException). */
    private fun tryEcuKm(elm: ElmTransport, quiet: Boolean = true): Boolean {
        // Requête propre aux PSA : jamais envoyée à une autre voiture (CarProfile.kt).
        if (!CarProfile.PSA_ECU_ODOMETER) return false
        val km = try {
            readEcuKm(elm, quiet)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            ObdLog.log("KM", "erreur : ${e.message ?: e.javaClass.simpleName}")
            null
        } ?: return false
        if (!applyEcuKm(km)) {
            ObdLog.log("KM", "$km km refusé : plus petit que le dernier relevé moteur (lecture faussée)")
            return false
        }
        _diag.update { it.copy(ecuKm = km) }
        return true
    }

    /**
     * Le kilométrage du calculateur devient le nouveau point de départ (le GPS complète ensuite). Faux si la valeur
     * est refusée (plus petite que le dernier relevé moteur : lecture faussée).
     */
    private fun applyEcuKm(km: Int): Boolean = Vehicle.setOdometerFromEcu(km)

    /** Interface commune ELM327 (Bluetooth classique SPP ou Bluetooth Low Energy GATT). */
    private abstract class ElmTransport(val name: String) : Closeable {
        abstract fun cmd(command: String, timeoutMs: Long = 1500): String

        /** Octets de données d'un PID du mode 01, ou null si absent ou plus court que `minBytes`. */
        fun pid(pid: String, timeoutMs: Long = 1500, minBytes: Int = 1): IntArray? {
            val hex = cmd("01$pid", timeoutMs).uppercase().filter { it.isLetterOrDigit() }
            val i = hex.indexOf("41$pid")
            if (i < 0) return null
            val data = hex.substring(i + 4).chunked(2).takeWhile { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
            return data.toIntArray().takeIf { it.size >= minBytes }
        }

        fun readBatteryVolts(): Float? {
            val resp = cmd("ATRV", 1200)
            val v = VOLTS_REGEX.find(resp)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
            return v?.takeIf { it in 8.0f..18.5f }
        }

        companion object {
            private val VOLTS_REGEX = Regex("(\\d{1,2}[.,]\\d{1,2})")
        }
    }

    /** Transport Bluetooth classique RFCOMM SPP (Android-Vlink). */
    private class SppTransport(private val socket: BluetoothSocket, label: String) : ElmTransport(label) {
        private val input = socket.inputStream
        private val output = socket.outputStream

        override fun cmd(command: String, timeoutMs: Long): String {
            output.write("$command\r".toByteArray(Charsets.US_ASCII))
            output.flush()
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val c = input.read()
                    if (c < 0) throw IOException("socket fermé")
                    if (c == '>'.code) break
                    sb.append(c.toChar())
                } else {
                    Thread.sleep(5)
                }
            }
            return sb.toString()
        }

        override fun close() {
            runCatching { socket.close() }
        }

        companion object {
            @SuppressLint("MissingPermission")
            fun connect(device: BluetoothDevice): SppTransport {
                val sock = runCatching { device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
                    .getOrElse { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
                return SppTransport(sock, "${device.name ?: "OBD"} (SPP)")
            }
        }
    }

    /** Transport Bluetooth Low Energy GATT (Vgate iCar Pro 2S « IOS-Vlink » : service 18F0 / 2AF0 / 2AF1 ou FFF0). */
    @SuppressLint("MissingPermission")
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private class BleTransport(
        private val gatt: BluetoothGatt,
        private val writeChar: BluetoothGattCharacteristic,
        private val rxLock: java.lang.Object,
        private val rxBuf: StringBuilder,
        private val stateRef: AtomicReference<Boolean>,
        private val writeLatchRef: AtomicReference<CountDownLatch?>,
        label: String,
    ) : ElmTransport(label) {

        @Suppress("DEPRECATION")
        override fun cmd(command: String, timeoutMs: Long): String {
            if (!stateRef.get()) throw IOException("BLE déconnecté")
            synchronized(rxLock) { rxBuf.setLength(0) }

            val payload = "$command\r".toByteArray(Charsets.US_ASCII)
            val props = writeChar.properties
            val writeNoResp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + 20, payload.size)
                val chunk = payload.copyOfRange(offset, end)
                val latch = CountDownLatch(1)
                writeLatchRef.set(latch)
                writeChar.writeType = if (writeNoResp) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                writeChar.value = chunk
                if (!gatt.writeCharacteristic(writeChar)) {
                    Thread.sleep(15)
                    if (!gatt.writeCharacteristic(writeChar)) throw IOException("échec écriture BLE")
                }
                latch.await(450, TimeUnit.MILLISECONDS)
                offset = end
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(rxLock) {
                while (System.currentTimeMillis() < deadline) {
                    if (!stateRef.get()) throw IOException("BLE déconnecté")
                    val idx = rxBuf.indexOf(">")
                    if (idx >= 0) return rxBuf.substring(0, idx)
                    val rem = deadline - System.currentTimeMillis()
                    if (rem > 0) rxLock.wait(minOf(rem, 40L))
                }
                return rxBuf.toString()
            }
        }

        override fun close() {
            stateRef.set(false)
            synchronized(rxLock) { rxLock.notifyAll() }
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }

        companion object {
            @Suppress("DEPRECATION", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            fun connect(ctx: Context, device: BluetoothDevice, labelOverride: String? = null): BleTransport {
                val rxLock = java.lang.Object()
                val rxBuf = StringBuilder()
                val alive = AtomicReference(false)
                val readyLatch = CountDownLatch(1)
                val descLatch = CountDownLatch(1)
                val writeLatchRef = AtomicReference<CountDownLatch?>(null)
                var notifyFound: BluetoothGattCharacteristic? = null
                var writeFound: BluetoothGattCharacteristic? = null

                fun appendRx(bytes: ByteArray) {
                    if (bytes.isEmpty()) return
                    synchronized(rxLock) {
                        rxBuf.append(String(bytes, Charsets.US_ASCII))
                        if (rxBuf.indexOf(">") >= 0) rxLock.notifyAll()
                    }
                }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            alive.set(true)
                            g.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            alive.set(false)
                            readyLatch.countDown()
                            descLatch.countDown()
                            writeLatchRef.get()?.countDown()
                            synchronized(rxLock) { rxLock.notifyAll() }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val pair = pickUartCharacteristics(g)
                            if (pair != null) {
                                notifyFound = pair.first
                                writeFound = pair.second
                            }
                        }
                        readyLatch.countDown()
                    }

                    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        descLatch.countDown()
                    }

                    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        writeLatchRef.get()?.countDown()
                    }

                    @Deprecated("Appelée sur Android 8..12")
                    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                        val v = characteristic.value ?: return
                        appendRx(v)
                    }

                    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                        appendRx(value)
                    }
                }

                val gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw IOException("GATT indisponible")

                val ok = readyLatch.await(5_000, TimeUnit.MILLISECONDS) && alive.get()
                val nChar = notifyFound
                val wChar = writeFound
                if (!ok || nChar == null || wChar == null) {
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    throw IOException("service UART BLE introuvable")
                }

                gatt.setCharacteristicNotification(nChar, true)
                nChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                    val useIndicate = (nChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
                        (nChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    cccd.value = if (useIndicate) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    if (gatt.writeDescriptor(cccd)) {
                        descLatch.await(1_500, TimeUnit.MILLISECONDS)
                    }
                }

                val label = "${labelOverride ?: device.name ?: "Vgate"} (BLE)"
                return BleTransport(gatt, wChar, rxLock, rxBuf, alive, writeLatchRef, label)
            }

            private fun pickUartCharacteristics(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
                val services = gatt.services.orEmpty()
                val ordered = BLE_SERVICE_UUIDS.mapNotNull { gatt.getService(it) } +
                    services.filter { s ->
                        val u = s.uuid.toString().lowercase()
                        !u.startsWith("00001800") && !u.startsWith("00001801") && !u.startsWith("0000180a")
                    }
                for (svc in ordered.distinctBy { it.uuid }) {
                    val chars = svc.characteristics.orEmpty()
                    // Priorité explicite : vLinker MC+ (BEF8D6C9 = notify + write sur la même caractéristique),
                    // puis Vgate iCar Pro / 2S (2AF0 = notify, 2AF1 = write).
                    svc.getCharacteristic(VLINKER_CHAR)?.let { c -> return c to c }
                    val nChar = svc.getCharacteristic(VGATE_NOTIFY) ?: chars.firstOrNull {
                        (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                    }
                    val wChar = svc.getCharacteristic(VGATE_WRITE) ?: chars.firstOrNull {
                        it != nChar && (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    } ?: chars.firstOrNull {
                        (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    }
                    if (nChar != null && wChar != null) return nChar to wChar
                }
                return null
            }
        }
    }
}
