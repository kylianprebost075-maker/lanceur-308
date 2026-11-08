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
import fr.p308.lanceur.maintenance.DueLevel
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.maintenance.MaintenanceItem
import fr.p308.lanceur.maintenance.due
import fr.p308.lanceur.maintenance.label
import fr.p308.lanceur.drive.Speed
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
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) = GpsMonitor.onGnss(status)
    }
    private var lastFix: Location? = null
    private var gpsOn = false

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

        overlay = DoorOverlay(this)
        banner = AlertBanner(this)
        perf = PerfOverlay(this) { PerfPanel.armed.value = false }
        scope.launch {
            var wasForeground = AppVisibility.foreground.value
            combine(Engine.state, AppVisibility.foreground, PerfPanel.armed) { e, fg, armed -> Triple(e, fg, armed) }
                .collect { (e, fg, armed) ->
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
                checkBattery()
                delay(1000)
            }
        }
        scope.launch {
            combine(Vehicle.state.map { it.open }.distinctUntilChanged(), AppVisibility.foreground) { open, fg -> open to fg }
                .collect { (open, fg) -> if (open.isEmpty() || fg) overlay.hide() else overlay.show("⚠  " + describeOpen(open)) }
        }
        scope.launch {
            combine(Vehicle.state.map { it.odometerKm }.distinctUntilChanged(), Maintenance.items) { km, items -> km to items }
                .collect { (km, items) -> checkMaintenance(km, items) }
        }
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
        if (b.level == lastBatteryLevel) return
        lastBatteryLevel = b.level
        val msg = b.message ?: return
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
            if (sent.getString(item.id, null) == due.level.name) return@forEach
            val title = if (due.level == DueLevel.LATE) "Entretien dépassé : ${item.name}" else "Entretien à prévoir : ${item.name}"
            nm.notify(item.id.hashCode(), NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(title)
                .setContentText(due.label())
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            sent.edit().putString(item.id, due.level.name).apply()
        }
    }

    companion object {
        private const val CH_SERVICE = "service"
        private const val CH_ALERT = "alerts"
        private const val BATTERY_NOTIF = 4242

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, VehicleWatchService::class.java))
    }
}
