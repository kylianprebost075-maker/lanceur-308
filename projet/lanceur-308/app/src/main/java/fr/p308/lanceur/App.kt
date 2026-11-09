package fr.p308.lanceur

import android.app.Application
import fr.p308.lanceur.drive.Chrono
import fr.p308.lanceur.drive.TripLog
import fr.p308.lanceur.vehicle.CrankWatch
import fr.p308.lanceur.maintenance.Fuel
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.vehicle.GpsMonitor
import fr.p308.lanceur.vehicle.Vehicle

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UiPrefs.init(this)
        Vehicle.init(this)
        Maintenance.init(this)
        Fuel.init(this)
        Chrono.init(this)
        GpsMonitor.init(this)
        // Après Vehicle.init : le mode démo est déjà connu, le trajet en cours peut être repris.
        TripLog.init(this)
        CrankWatch.init(this)
        fr.p308.lanceur.vehicle.GearCalib.init(this)
        fr.p308.lanceur.vehicle.Warmup.init(this)
        fr.p308.lanceur.vehicle.ObdLog.init(this)
        fr.p308.lanceur.maintenance.History.init(this)
        fr.p308.lanceur.vehicle.FapRegen.init(this)
        fr.p308.lanceur.sound.SoundPrefs.init(this)
        fr.p308.lanceur.optim.Optim.init(this)
    }
}
