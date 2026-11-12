package fr.p308.lanceur.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.p308.lanceur.AppVisibility
import fr.p308.lanceur.MainActivity
import fr.p308.lanceur.PerfPanel
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.R
import fr.p308.lanceur.backup.AutoBackup
import fr.p308.lanceur.maintenance.DueLevel
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.maintenance.MaintenanceItem
import fr.p308.lanceur.maintenance.due
import fr.p308.lanceur.maintenance.label
import fr.p308.lanceur.drive.Speed
import fr.p308.lanceur.drive.TripLog
import fr.p308.lanceur.vehicle.CrankRecord
import fr.p308.lanceur.vehicle.CrankVerdict
import fr.p308.lanceur.vehicle.CrankWatch
import fr.p308.lanceur.vehicle.BatteryLevel
import fr.p308.lanceur.vehicle.CanBusSource
import fr.p308.lanceur.vehicle.BatteryWatch
import fr.p308.lanceur.vehicle.Engine
import fr.p308.lanceur.vehicle.GpsMonitor
import fr.p308.lanceur.vehicle.Vehicle
import fr.p308.lanceur.vehicle.describeOpen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VehicleWatchService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: DoorOverlay
    private lateinit var banner: AlertBanner
    private lateinit var perf: PerfOverlay
    private var lastBatteryLevel = BatteryLevel.UNKNOWN
    private var lastDrainStep = 0
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) = GpsMonitor.onGnss(status)
    }
    private var lastFix: Location? = null
    private var gpsOn = false
    private var ticks = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Surveillance véhicule", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertes entretien", NotificationManager.IMPORTANCE_HIGH))
        startForeground(1, NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Lanceur 308 actif")
            .setOngoing(true)
            .build())

        fr.p308.lanceur.sound.Voice.init(this)
        overlay = DoorOverlay(this)
        banner = AlertBanner(this)
        perf = PerfOverlay(this) { PerfPanel.armed.value = false }
        scope.launch {
            var wasForeground = AppVisibility.foreground.value
            var wasCarPlay = false
            combine(Engine.state, AppVisibility.foreground, PerfPanel.armed) { e, fg, armed -> Triple(e, fg, armed) }
                .collect { (e, fg, armed) ->
                    // CarPlay vient de s'ouvrir : on libère la mémoire (applis en arrière-plan seulement).
                    val carPlay = armed && !fg
                    if (carPlay && !wasCarPlay && !Vehicle.demo) fr.p308.lanceur.optim.Optim.cleanForCarPlay(this@VehicleWatchService)
                    wasCarPlay = carPlay
                    // Retour dans le Lanceur : le panneau ne revient qu'au prochain lancement de CarPlay.
                    if (fg && !wasForeground) PerfPanel.armed.value = false
                    wasForeground = fg
                    if (armed && !fg && UiPrefs.perfOnCarPlay) perf.show(e, UiPrefs.perfOnRight) else perf.hide()
                }
        }
        scope.launch {
            while (isActive) {
                GpsMonitor.tick()
                if (!Vehicle.demo) Speed.decayIfStale()
                TripLog.tick(Engine.state.value)
                fr.p308.lanceur.vehicle.Warmup.tick(Engine.state.value, fr.p308.lanceur.drive.Trip.state.value.currentKmh,
                    Vehicle.state.value.outsideTempC)
                AutoBackup.tick(this@VehicleWatchService)
                checkBattery()
                if (fr.p308.lanceur.vehicle.CarProfile.HAS_TURBO)
                    fr.p308.lanceur.vehicle.TurboCool.tick(Engine.state.value, fr.p308.lanceur.drive.Trip.state.value.currentKmh)
                if (fr.p308.lanceur.vehicle.CarProfile.HAS_DPF) fr.p308.lanceur.vehicle.FapRegen.tick(Engine.state.value, fr.p308.lanceur.drive.Trip.state.value.currentKmh,
                    Vehicle.state.value.odometerKm)
                fr.p308.lanceur.sound.BassAuto.tick(this@VehicleWatchService)
                voiceAlerts()
                if (++ticks % 2 == 0) CarPlayWatch.tick(this@VehicleWatchService)
                // Autonomie : recalculée toutes les 30 s (le kilométrage avance en roulant).
                // Mémoire de l'autoradio : libérée toute seule si elle devient basse (v0.18).
                if (ticks % 30 == 15 && !Vehicle.demo) fr.p308.lanceur.optim.Optim.autoTick(this@VehicleWatchService)
                if (ticks % 30 == 1) {
                    fr.p308.lanceur.drive.FuelRange.update(
                        fr.p308.lanceur.maintenance.Fuel.entries.value, TripLog.history.value, Vehicle.state.value.odometerKm,
                    )
                    checkRange()
                }
                fr.p308.lanceur.backup.Import.check(this@VehicleWatchService)?.let(::notifyImport)
                delay(1000)
            }
        }
        scope.launch {
            combine(Vehicle.state.map { it.open }.distinctUntilChanged(), AppVisibility.foreground) { open, fg -> open to fg }
                .collect { (open, fg) -> if (open.isEmpty() || fg) overlay.hide() else overlay.show("⚠  " + describeOpen(open)) }
        }
        scope.launch {
            // v0.33 : plein repéré tout seul (l'autonomie de l'ordinateur de bord remonte d'un coup) : on l'annonce, et
            // le calcul du Lanceur repart de ce niveau. Seuls les pleins repérés après le lancement comptent.
            var seen = fr.p308.lanceur.drive.TankGauge.state.value.lastFill?.atMs ?: 0L
            fr.p308.lanceur.drive.TankGauge.state.collect { g ->
                val fill = g.lastFill ?: return@collect
                if (fill.atMs <= seen) return@collect
                seen = fill.atMs
                val odo = Vehicle.state.value.odometerKm
                if (odo != null && g.liters != null) {
                    fr.p308.lanceur.maintenance.Fuel.setLevel(g.liters.toFloat(), odo)
                    fr.p308.lanceur.drive.FuelRange.update(fr.p308.lanceur.maintenance.Fuel.entries.value, TripLog.history.value, odo)
                }
                val msg = "Plein détecté : ≈ +%.0f L (autonomie %d → %d km)".format(java.util.Locale.FRANCE, fill.liters, fill.fromKm, fill.toKm)
                fr.p308.lanceur.vehicle.ObdLog.log("PLEIN", msg)
                fr.p308.lanceur.sound.Voice.say("Plein détecté : environ %.0f litres ajoutés.".format(java.util.Locale.FRANCE, fill.liters))
                getSystemService(NotificationManager::class.java).notify(FILL_NOTIF, NotificationCompat.Builder(this@VehicleWatchService, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_car)
                    .setContentTitle("Plein détecté")
                    .setContentText("$msg. Ajoute le prix dans Entretien → Pleins.")
                    .setAutoCancel(true)
                    .build())
                banner.show("⛽  $msg", 0xE62C7A3F.toInt(), 12_000)
            }
        }
        scope.launch {
            // v0.32 : son « Passagers » quand une porte passager s'ouvre (les ceintures ne sont pas transmises).
            // Nouveau trajet (autoradio qui vient de démarrer) : un « Passagers » mis automatiquement revient à « Seul ».
            val snd = fr.p308.lanceur.sound.SoundPrefs
            if (fr.p308.lanceur.sound.SeatAuto.shouldRevert(snd.seatMode, snd.seatAuto, android.os.SystemClock.elapsedRealtime())) {
                snd.updateSeatMode(fr.p308.lanceur.sound.SeatMode.SOLO)
                launch {
                    delay(20_000) // le temps que le service d'accessibilité soit prêt après le démarrage
                    fr.p308.lanceur.sound.ChampService.apply(this@VehicleWatchService, fr.p308.lanceur.sound.SeatMode.SOLO)
                }
            }
            var before = Vehicle.state.value.open
            Vehicle.state.map { it.open }.distinctUntilChanged().collect { open ->
                val opened = open - before
                before = open
                if (Vehicle.demo) return@collect
                if (fr.p308.lanceur.sound.SeatAuto.shouldSwitch(opened, snd.seatMode, snd.autoPassengers,
                        fr.p308.lanceur.drive.Trip.state.value.currentKmh)) {
                    snd.updateSeatMode(fr.p308.lanceur.sound.SeatMode.PASSENGERS, auto = true)
                    val msg = fr.p308.lanceur.sound.ChampService.apply(this@VehicleWatchService, fr.p308.lanceur.sound.SeatMode.PASSENGERS)
                    fr.p308.lanceur.vehicle.ObdLog.log("SON", "Porte passager ouverte : son pour les passagers ($msg)")
                    banner.show("🎵  Porte passager ouverte : son réglé pour les passagers (Son → Seul pour revenir)", 0xE62C58BD.toInt(), 8_000)
                }
            }
        }
        scope.launch {
            // Usure de l'huile recalculée quand les trajets ou l'entretien changent, avant de vérifier les échéances.
            combine(Vehicle.state.map { it.odometerKm }.distinctUntilChanged(), Maintenance.items, TripLog.history) { km, items, trips ->
                fr.p308.lanceur.maintenance.OilWear.recompute(items, trips)
                km to items
            }.collect { (km, items) -> checkMaintenance(km, items) }
        }
        scope.launch {
            // Santé de la batterie dans le temps : alerte quand elle se dégrade (une fois par niveau).
            CrankWatch.history.collect { list ->
                if (!Vehicle.demo) fr.p308.lanceur.maintenance.BatteryHealthModel.evaluate(list)?.let(::alertBatteryHealth)
            }
        }
        scope.launch {
            // Consommation corrigée par les pleins, puis conseil FAP (au plus une alerte tous les 3 jours).
            combine(fr.p308.lanceur.maintenance.Fuel.entries, TripLog.history) { f, t -> f to t }.collect { (fills, trips) ->
                fr.p308.lanceur.drive.FuelModel.recompute(fills, trips)
                fr.p308.lanceur.drive.FuelRange.update(fills, trips, Vehicle.state.value.odometerKm)
                val fap = if (Vehicle.demo) null else fr.p308.lanceur.drive.FapAdvice.evaluate(trips)
                fr.p308.lanceur.drive.FapAdvice.current = fap
                fap?.let(::alertFap)
            }
        }
        scope.launch {
            // Régénération du FAP : bandeau au début, notification si elle a été coupée, conseil FAP recalculé à la fin.
            var wasActive = false
            var seen = fr.p308.lanceur.vehicle.FapRegen.state.value.records.firstOrNull()?.endMs ?: 0L
            fr.p308.lanceur.vehicle.FapRegen.state.collect { r ->
                if (Vehicle.demo) return@collect
                if (r.active && !wasActive) {
                    fr.p308.lanceur.sound.Voice.say("Régénération du filtre à particules en cours. Évite de couper le moteur.")
                    if (!AppVisibility.foreground.value) {
                        banner.show("🔥  Régénération du FAP probable : roule encore 10 à 15 min sans couper si tu peux",
                            0xE6C77700.toInt(), 15_000)
                    }
                }
                wasActive = r.active
                val last = r.records.firstOrNull() ?: return@collect
                if (last.endMs <= seen) return@collect
                seen = last.endMs
                if (!last.interrupted) fr.p308.lanceur.sound.Voice.say("Régénération du filtre à particules terminée.")
                val fap = fr.p308.lanceur.drive.FapAdvice.evaluate(TripLog.history.value)
                fr.p308.lanceur.drive.FapAdvice.current = fap
                if (last.interrupted) alertRegenCut(last)
            }
        }
        scope.launch {
            // Seuls les démarrages mesurés après le lancement du service déclenchent une alerte.
            var seen = CrankWatch.history.value.firstOrNull()?.atMs ?: 0L
            CrankWatch.history.collect { list ->
                val last = list.firstOrNull() ?: return@collect
                if (last.atMs <= seen) return@collect
                seen = last.atMs
                if (last.verdict == CrankVerdict.WEAK) alertWeakStart(last)
            }
        }
    }

    /** Conseil voie rapide pour le FAP : notification + bandeau, au plus tous les 3 jours (ou si ça s'aggrave). */
    private fun alertFap(s: fr.p308.lanceur.drive.FapStatus) {
        val p = getSharedPreferences("alerts", Context.MODE_PRIVATE)
        if (s.level == 0) {
            p.edit().putInt("fapLevel", 0).apply()
            return
        }
        val now = System.currentTimeMillis()
        if (s.level <= p.getInt("fapLevel", 0) && now - p.getLong("fapAt", 0L) < 3 * 86_400_000L) return
        p.edit().putInt("fapLevel", s.level).putLong("fapAt", now).apply()
        getSystemService(NotificationManager::class.java).notify(FAP_NOTIF, NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Filtre à particules")
            .setContentText(s.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.text))
            .setAutoCancel(true)
            .build())
        if (!AppVisibility.foreground.value) banner.show("🛣  ${s.text}", 0xE6C77700.toInt(), 15_000)
    }

    /** Moteur coupé pendant une régénération : le filtre reste à moitié nettoyé, une voie rapide l'aide à finir. */
    private fun alertRegenCut(r: fr.p308.lanceur.vehicle.RegenRecord) {
        val msg = "Régénération du FAP coupée après ${r.minutes} min (moteur arrêté). Elle reprendra seule ; " +
            "20 min de voie rapide au prochain trajet l'aident à finir."
        getSystemService(NotificationManager::class.java).notify(REGEN_NOTIF, NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Filtre à particules")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .build())
    }

    private var wasCooled = false
    private var overheat = false

    /** Annonces vocales qui suivent l'état moteur (au passage, pas en continu). */
    private fun voiceAlerts() {
        val turbo = fr.p308.lanceur.vehicle.TurboCool.state.value
        val cooled = turbo.justCooled && turbo.stopped
        if (cooled && !wasCooled) fr.p308.lanceur.sound.Voice.say("Turbo refroidi, tu peux couper le moteur.")
        wasCooled = cooled
        val c = Engine.state.value.coolantC
        if (c != null && c >= 105 && !overheat) {
            overheat = true
            fr.p308.lanceur.sound.Voice.say("Attention, surchauffe du moteur. Arrête-toi dès que possible.")
        } else if (c != null && c < 100) overheat = false
    }

    private var rangeAlerted = false

    /** Réserve proche (autonomie estimée sous 50 km) : un bandeau une fois, réarmé après un plein. */
    private fun checkRange() {
        val r = fr.p308.lanceur.drive.FuelRange.state.value ?: return
        if (r.km > 80) rangeAlerted = false
        if (r.km >= 50 || rangeAlerted || Vehicle.demo) return
        rangeAlerted = true
        val msg = "Réserve bientôt : ≈ ${r.km} km d'autonomie (estimée depuis ton dernier plein)"
        fr.p308.lanceur.sound.Voice.say("Réserve bientôt : environ ${r.km} kilomètres d'autonomie.")
        if (!AppVisibility.foreground.value) banner.show("⛽  $msg", 0xE6C77700.toInt(), 12_000)
    }

    /** Factures importées depuis la clé : notification + bandeau (Entretien → Historique pour les voir). */
    private fun notifyImport(msg: String) {
        getSystemService(NotificationManager::class.java).notify(IMPORT_NOTIF, NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Entretien")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$msg Détail : Entretien → Historique."))
            .setAutoCancel(true)
            .build())
        banner.show("🧾  $msg", 0xE62E7D32.toInt(), 12_000)
    }

    /** Batterie qui se dégrade d'un démarrage à l'autre : prévient avant la panne (une fois par aggravation). */
    private fun alertBatteryHealth(h: fr.p308.lanceur.maintenance.BatteryHealth) {
        val p = getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val before = p.getInt("batHealth", 0)
        p.edit().putInt("batHealth", h.level).apply()
        if (h.level <= before) return
        val msg = "${h.title} : ${h.text}"
        getSystemService(NotificationManager::class.java).notify(HEALTH_NOTIF, NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Santé de la batterie")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .build())
        if (!AppVisibility.foreground.value) banner.show("🔋  $msg", 0xE6C77700.toInt(), 15_000)
    }

    /** Démarrage avec une chute de tension anormale : notification + bandeau par-dessus CarPlay. */
    private fun alertWeakStart(r: CrankRecord) {
        val msg = (r.minV?.let { "Batterie faible au démarrage : %.1f V au repos, %.1f V au démarreur. Fais-la tester."
            .format(java.util.Locale.FRANCE, r.restV, it) }
            ?: "Batterie faible : %.1f V au repos avant le démarrage. Fais-la tester.".format(java.util.Locale.FRANCE, r.restV))
        getSystemService(NotificationManager::class.java).notify(CRANK_NOTIF, NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Batterie")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .build())
        if (!AppVisibility.foreground.value) banner.show("🔋  $msg", 0xE6C77700.toInt(), 15_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startGps()
        if (!Vehicle.demo) Engine.start(this)
        CanBusSource.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        if (gpsOn) getSystemService(LocationManager::class.java).run {
            removeUpdates(this@VehicleWatchService)
            unregisterGnssStatusCallback(gnss)
        }
        overlay.hide()
        banner.hide()
        perf.hide()
        CanBusSource.stop(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun startGps() {
        if (gpsOn) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            getSystemService(LocationManager::class.java).registerGnssStatusCallback(gnss)
        }.onSuccess { gpsOn = true }
    }

    /** Odomètre GPS : on ignore les points imprécis, l'arrêt (dérive) et les sauts impossibles. */
    override fun onLocationChanged(loc: Location) {
        GpsMonitor.onLocation(loc)
        fr.p308.lanceur.vehicle.GpsClock.onLocation(loc)
        if (!Vehicle.demo) {
            val gpsKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            Speed.report(if (gpsKmh < 1.8f) 0f else gpsKmh, fromObd = false)
        }
        if (loc.hasAccuracy() && loc.accuracy > 25f) return
        val prev = lastFix
        lastFix = loc
        if (prev == null) return
        val dt = (loc.time - prev.time) / 1000f
        if (dt <= 0f) return
        val d = prev.distanceTo(loc)
        if (d / dt in 1.5f..70f) Vehicle.addGpsMeters(d)
    }

    @Deprecated("Appelé seulement sur les anciennes versions d'Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** Alerte batterie : notification + bandeau par-dessus CarPlay, une fois par changement de niveau. */
    private fun checkBattery() {
        val engine = Engine.state.value
        val b = BatteryWatch.update(Vehicle.state.value.batteryV ?: engine.voltage, engine.rpm)
        // v0.31 : rappel toutes les 20 min quand le moteur est coupé et l'autoradio allumé (la batterie se vide).
        val step = BatteryWatch.drainStep(b.engineOffMin)
        if (step > lastDrainStep && !Vehicle.demo) BatteryWatch.drainMessage(b)?.let { msg ->
            if (step == 1) fr.p308.lanceur.sound.Voice.say("Moteur coupé depuis ${b.engineOffMin} minutes : la batterie se vide.")
            getSystemService(NotificationManager::class.java).notify(DRAIN_NOTIF,NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle("Batterie")
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .build())
            banner.show("🔋  $msg", 0xE6C77700.toInt(), 15_000)
        }
        lastDrainStep = step
        if (b.level == lastBatteryLevel) return
        lastBatteryLevel = b.level
        val msg = b.message ?: return
        fr.p308.lanceur.sound.Voice.say(msg)
        getSystemService(NotificationManager::class.java).notify(BATTERY_NOTIF, NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Batterie")
            .setContentText(msg)
            .setAutoCancel(true)
            .build())
        if (!AppVisibility.foreground.value) {
            banner.show("🔋  $msg", if (b.level == BatteryLevel.LOW) 0xE6C77700.toInt() else 0xE6B71C1C.toInt())
        }
    }

    /** Une notification par élément, à chaque passage en « bientôt » puis en « dépassé ». */
    private fun checkMaintenance(km: Int?, items: List<MaintenanceItem>) {
        val sent = getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        items.forEach { item ->
            val due = item.due(km)
            val alert = due.level == DueLevel.SOON || due.level == DueLevel.LATE
            if (!alert) {
                sent.edit().remove(item.id).apply()
                return@forEach
            }
            // Échéances à date (contrôle technique, assurance…) : 2e rappel une semaine avant.
            val tag = if (due.level == DueLevel.SOON && (due.daysLeft ?: 99L) <= 7L) "SOON7" else due.level.name
            if (sent.getString(item.id, null) == tag) return@forEach
            val title = when {
                due.level == DueLevel.LATE -> "Entretien dépassé : ${item.name}"
                tag == "SOON7" -> "Plus qu'une semaine : ${item.name}"
                else -> "Entretien à prévoir : ${item.name}"
            }
            nm.notify(item.id.hashCode(), NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(title)
                .setContentText(due.label())
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            // Les notifications de l'autoradio passent souvent inaperçues : bandeau aussi par-dessus CarPlay.
            if (!AppVisibility.foreground.value) {
                banner.show("🔧  $title (${due.label()})", if (due.level == DueLevel.LATE) 0xE6B71C1C.toInt() else 0xE6C77700.toInt())
            }
            sent.edit().putString(item.id, tag).apply()
        }
    }

    companion object {
        private const val CH_SERVICE = "service"
        private const val CH_ALERT = "alerts"
        private const val BATTERY_NOTIF = 4242
        private const val FILL_NOTIF = 4260
        private const val DRAIN_NOTIF = 4261
        private const val CRANK_NOTIF = 4243
        private const val FAP_NOTIF = 4244
        private const val HEALTH_NOTIF = 4245
        private const val IMPORT_NOTIF = 4246
        private const val REGEN_NOTIF = 4247

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, VehicleWatchService::class.java))
    }
}
