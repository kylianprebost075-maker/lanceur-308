package fr.p308.lanceur.backup

import android.content.Context
import fr.p308.lanceur.drive.TripLog
import fr.p308.lanceur.vehicle.CrankWatch
import fr.p308.lanceur.vehicle.Vehicle
import fr.p308.lanceur.vehicle.Warmup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sauvegarde automatique de tout (entretien, pleins, kilométrage, trajets, batterie, chauffes, rapports appris,
 * réglages) dans la mémoire interne ET sur la clé USB si elle est branchée : au démarrage du service, à la fin de
 * chaque trajet, après un démarrage ou une chauffe enregistrés, quand une clé est branchée, et au moins toutes les 6 h.
 * Vérifié une fois par minute ; jamais en mode démo.
 */
object AutoBackup {
    private const val EVERY_MS = 6 * 3_600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCheckMs = 0L
    private var lastKey: String? = null
    private var lastUsb = -1

    @Volatile
    private var busy = false

    @Volatile
    private var infoWritten = false

    /** Appelé chaque seconde par le service (thread principal). */
    fun tick(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastCheckMs < 60_000L) return
        lastCheckMs = nowMs
        if (Vehicle.demo || busy) return
        val p = ctx.getSharedPreferences("autosave", Context.MODE_PRIVATE)
        // Ce qui change quand quelque chose de nouveau est enregistré.
        val key = listOf(
            TripLog.history.value.firstOrNull()?.startMs, CrankWatch.history.value.firstOrNull()?.atMs,
            Warmup.history.value.firstOrNull()?.startMs, Vehicle.state.value.odometerKm,
        ).joinToString("|")
        val usb = Backup.usbCount(ctx)
        // Fiche technique de l'autoradio (v0.19) : écrite sur la clé une fois par lancement, dès qu'une clé est là.
        if (usb > 0 && !infoWritten) {
            infoWritten = true
            val appCtx = ctx.applicationContext
            scope.launch { if (runCatching { DeviceInfo.write(appCtx) }.getOrDefault(0) == 0) infoWritten = false }
        }
        // Plantages (v0.20) : signalés au journal et recopiés sur la clé ; vérifié une fois par minute (rapide si rien).
        run {
            val appCtx = ctx.applicationContext
            scope.launch { runCatching { CrashLog.syncToKeys(appCtx) } }
        }
        val due = nowMs - p.getLong("at", 0L) > EVERY_MS ||
            (lastKey != null && key != lastKey) ||
            (lastUsb >= 0 && usb > lastUsb)
        lastKey = key
        lastUsb = usb
        if (!due) return
        busy = true
        val app = ctx.applicationContext
        scope.launch {
            val msg = runCatching { Backup.save(app) }.getOrElse { "Échec : ${it.message}" }
            p.edit().putLong("at", System.currentTimeMillis()).putString("msg", msg).putBoolean("usb", usb > 0).apply()
            busy = false
        }
    }

    /** Texte pour les Réglages. */
    fun lastText(ctx: Context): String {
        val p = ctx.getSharedPreferences("autosave", Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (at == 0L) return "Automatique : pas encore faite"
        val whenTxt = SimpleDateFormat("d MMM à HH:mm", Locale.FRANCE).format(Date(at))
        return "Automatique : dernière le $whenTxt, " + if (p.getBoolean("usb", false)) "copiée sur la clé USB" else "mémoire interne (clé absente)"
    }
}
