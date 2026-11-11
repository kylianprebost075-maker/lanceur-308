package fr.p308.lanceur.backup

import android.content.Context
import android.os.Environment
import fr.p308.lanceur.maintenance.History
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.maintenance.MaintenanceItem
import fr.p308.lanceur.vehicle.Vehicle
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Import de factures depuis la clé USB, pour ne rien saisir à la main : un fichier « import-entretien.json »
 * posé dans Android/data/fr.p308.lanceur/files/ de la clé (préparé sur le PC à partir des photos des factures).
 * Contenu : interventions pour l'historique, dates et km des derniers entretiens, kilométrage connu.
 * Fusion prudente : un entretien n'est mis à jour que si l'import est plus récent que ce qui est déjà noté,
 * le kilométrage seulement s'il est plus grand. Le fichier est ensuite renommé « .importé » (pas de double import).
 * Vérifié une fois par minute par le service.
 */
object Import {
    const val FILE = "import-entretien.json"
    const val SOUND_FILE = "reglage-son.json"
    private var lastCheckMs = 0L

    /** Renvoie le message à afficher si un import vient d'être fait, sinon null. */
    fun check(ctx: Context, nowMs: Long = System.currentTimeMillis()): String? {
        if (nowMs - lastCheckMs < 60_000L || Vehicle.demo) return null
        lastCheckMs = nowMs
        // v0.30 : réglage du son mesuré au micro du PC (outils/son/reglage_auto.py), déposé par ADB.
        val son = UsbStorage.volumes(ctx).map { File(it.dir, SOUND_FILE) }.firstOrNull { it.exists() }
        if (son != null) {
            val msg = runCatching {
                val o = JSONObject(son.readText())
                o.optInt("resonanceHz", -1).takeIf { it >= 0 }?.let(fr.p308.lanceur.sound.SoundPrefs::updateResonance)
                if (o.has("bassAuto")) fr.p308.lanceur.sound.SoundPrefs.updateBassAuto(o.getBoolean("bassAuto"))
                // v0.31 : position « Seul » de la balance trouvée au micro, [crans →, crans ↑].
                o.optJSONArray("champSeul")?.takeIf { it.length() == 2 }?.let {
                    fr.p308.lanceur.sound.SoundPrefs.updateSoloChamp(it.getInt(0), it.getInt(1))
                }
                "Réglage du son reçu du PC : vibration des portes " +
                    (o.optInt("resonanceHz", 0).takeIf { it > 0 }?.let { "$it Hz" } ?: "aucune")
            }.getOrElse { "Réglage du son illisible : ${it.message}" }
            runCatching { son.renameTo(File(son.parentFile, "$SOUND_FILE.importé")) }
            fr.p308.lanceur.vehicle.ObdLog.log("SON", msg)
        }
        // Clé USB d'abord, puis la mémoire de l'autoradio (même dossier Android/data/fr.p308.lanceur/files/).
        val file = UsbStorage.volumes(ctx).sortedByDescending { it.usb }
            .map { File(it.dir, FILE) }.firstOrNull { it.exists() } ?: return null
        val msg = runCatching { apply(JSONObject(file.readText())) }.getOrElse { "Import des factures impossible : ${it.message}" }
        runCatching { file.renameTo(File(file.parentFile, "$FILE.importé")) }
        fr.p308.lanceur.vehicle.ObdLog.log("IMPORT", msg)
        return msg
    }

    private fun newer(a: LocalDate?, aKm: Int?, b: LocalDate?, bKm: Int?): Boolean = when {
        a != null && b != null -> a.isAfter(b)
        aKm != null && bKm != null -> aKm > bKm
        else -> b == null && bKm == null
    }

    fun apply(json: JSONObject): String {
        // 1. Historique.
        val hist = json.optJSONArray("history")
        val added = if (hist == null) 0 else History.merge(List(hist.length()) { History.decodeOne(hist.getJSONObject(it)) })

        // 2. Éléments d'entretien : repérés par un mot du nom (« vidange », « air »…), créés s'ils manquent.
        var updated = 0
        var created = 0
        json.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val match = o.optString("match").lowercase()
                val date = o.optString("lastDate").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
                val km = o.optInt("lastKm", -1).takeIf { it >= 0 }
                val existing = Maintenance.items.value.firstOrNull { it.name.lowercase().contains(match) }
                if (existing == null) {
                    Maintenance.upsert(
                        MaintenanceItem(
                            name = o.getString("name"), everyKm = o.optInt("everyKm"), everyMonths = o.optInt("everyMonths"),
                            lastKm = km, lastDate = date, note = o.optString("note"),
                        )
                    )
                    created++
                } else if (newer(date, km, existing.lastDate, existing.lastKm)) {
                    val note = o.optString("note")
                    Maintenance.upsert(existing.copy(lastKm = km ?: existing.lastKm, lastDate = date ?: existing.lastDate,
                        note = if (note.isNotEmpty()) note else existing.note))
                    updated++
                }
            }
        }

        // 3. Kilométrage : seulement vers le haut (le compteur ne recule pas).
        val odo = json.optInt("odometerKm", -1)
        val cur = Vehicle.state.value.odometerKm
        val odoMsg = if (odo > 0 && (cur == null || odo > cur)) {
            Vehicle.setOdometer(odo)
            " Kilométrage mis à $odo km."
        } else ""
        return "Factures importées : $added intervention(s) ajoutée(s) à l'historique, $updated entretien(s) mis à jour, " +
            "$created ajouté(s).$odoMsg"
    }
}
