package fr.p308.lanceur.backup

import android.content.Context
import android.os.Environment
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.drive.Chrono
import fr.p308.lanceur.maintenance.Fuel
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.vehicle.GpsMonitor
import fr.p308.lanceur.vehicle.Vehicle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sauvegarde sur clé USB (ou carte SD) : dans le dossier Android/data/fr.p308.lanceur/files/ de la clé,
 * accessible sans autorisation. Une copie reste aussi dans la mémoire interne.
 */
object Backup {
    private const val FILE = "sauvegarde-308.json"
    private val PREFS = listOf("maintenance", "vehicle", "ui", "fuel", "chrono", "trips", "battery", "gears", "warmup", "history", "fap")

    /** Nombre de clés USB / cartes SD branchées (pour sauvegarder dès qu'une clé arrive). */
    fun usbCount(ctx: Context): Int = volumes(ctx).count { it.second }

    private fun volumes(ctx: Context): List<Pair<File, Boolean>> =
        ctx.getExternalFilesDirs(null).filterNotNull().map { it to runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }

    fun save(ctx: Context): String {
        val json = JSONObject().put("version", 1).put("date", java.time.LocalDateTime.now().toString())
        for (name in PREFS) {
            val obj = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                obj.put(k, when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is Set<*> -> JSONObject().put("t", "s").put("v", JSONArray(v.toList()))
                    else -> JSONObject().put("t", "S").put("v", v.toString())
                })
            }
            json.put(name, obj)
        }
        val vols = volumes(ctx)
        val text = json.toString(2)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.FRANCE).format(java.util.Date())
        vols.forEach { (dir, _) ->
            runCatching { File(dir, FILE).writeText(text) }
            // Copies datées (les 10 dernières) : une mauvaise manip ne fait pas perdre l'historique.
            runCatching {
                val d = File(dir, "sauvegardes").apply { mkdirs() }
                File(d, "sauvegarde-308-$stamp.json").writeText(text)
                d.listFiles()?.filter { it.name.startsWith("sauvegarde-308-") }?.sortedByDescending { it.name }?.drop(10)
                    ?.forEach { it.delete() }
            }
        }
        val usb = vols.filter { it.second }
        copyGpsLogs(ctx)
        // Journal OBD et tableaux pour Excel : sur la clé seulement (la mémoire interne les a déjà).
        usb.forEach { (dir, _) ->
            runCatching {
                val j = File(dir, "journal").apply { mkdirs() }
                fr.p308.lanceur.vehicle.ObdLog.dir?.listFiles()?.forEach { it.copyTo(File(j, it.name), overwrite = true) }
            }
            runCatching { Export.writeAll(File(dir, "export")) }
        }
        return if (usb.isEmpty()) "Sauvegardé dans la mémoire interne (aucune clé USB détectée)"
        else "Sauvegardé sur la clé : Android/data/fr.p308.lanceur/files/ (sauvegarde, journal OBD, tableaux Excel dans « export »)"
    }

    /** Copie les journaux GPS sur la clé (dossier gps/ à côté de la sauvegarde). */
    fun copyGpsLogs(ctx: Context): String {
        val usb = volumes(ctx).filter { it.second }.map { it.first }
        if (usb.isEmpty()) return "Aucune clé USB détectée"
        val logs = GpsMonitor.dir.listFiles()?.filter { it.extension == "csv" }.orEmpty()
        usb.forEach { dir -> File(dir, "gps").mkdirs(); logs.forEach { runCatching { it.copyTo(File(dir, "gps/${it.name}"), overwrite = true) } } }
        return "${logs.size} journal(aux) GPS copié(s) sur la clé : Android/data/fr.p308.lanceur/files/gps/"
    }

    fun restore(ctx: Context): String {
        val file = volumes(ctx).sortedByDescending { it.second }.map { File(it.first, FILE) }.firstOrNull { it.exists() }
            ?: return "Aucune sauvegarde trouvée (ni sur la clé, ni en mémoire interne)"
        val json = JSONObject(file.readText())
        for (name in PREFS) {
            val obj = json.optJSONObject(name) ?: continue
            val edit = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            obj.keys().forEach { k ->
                val e = obj.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> edit.putBoolean(k, e.getBoolean("v"))
                    "i" -> edit.putInt(k, e.getInt("v"))
                    "l" -> edit.putLong(k, e.getLong("v"))
                    "f" -> edit.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> edit.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    else -> edit.putString(k, e.getString("v"))
                }
            }
            edit.commit()
        }
        UiPrefs.init(ctx)
        Maintenance.init(ctx)
        Fuel.init(ctx)
        Chrono.init(ctx)
        Vehicle.init(ctx)
        fr.p308.lanceur.drive.TripLog.init(ctx)
        fr.p308.lanceur.vehicle.CrankWatch.init(ctx)
        fr.p308.lanceur.vehicle.GearCalib.init(ctx)
        fr.p308.lanceur.vehicle.Warmup.init(ctx)
        fr.p308.lanceur.vehicle.Vehicle.reloadOdometer()
        fr.p308.lanceur.maintenance.History.init(ctx)
        fr.p308.lanceur.vehicle.FapRegen.init(ctx)
        return "Restauré depuis ${if (file.absolutePath.contains("emulated")) "la mémoire interne" else "la clé USB"} (sauvegarde du ${json.optString("date").take(10)})"
    }
}
