package fr.p308.lanceur.backup

import fr.p308.lanceur.drive.TripLog
import fr.p308.lanceur.maintenance.Fuel
import fr.p308.lanceur.vehicle.CrankWatch
import fr.p308.lanceur.vehicle.Warmup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tableaux pour Excel (ou LibreOffice) écrits sur la clé à chaque sauvegarde : trajets, pleins, démarrages,
 * chauffes. Format CSV « à la française » : point-virgule entre les colonnes, virgule décimale, UTF-8 avec BOM
 * (sinon Excel affiche mal les accents). Double-clic sur le fichier pour l'ouvrir.
 */
object Export {
    private val dateFmt get() = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
    private val timeFmt get() = SimpleDateFormat("HH:mm", Locale.FRANCE)

    private fun n(v: Double?, decimals: Int = 1): String = v?.let { "%.${decimals}f".format(Locale.FRANCE, it) } ?: ""
    private fun n(v: Float?, decimals: Int = 1): String = n(v?.toDouble(), decimals)

    private fun write(dir: File, name: String, header: List<String>, rows: List<List<Any?>>) {
        val text = buildString {
            append('﻿')
            append(header.joinToString(";")).append("\r\n")
            rows.forEach { r -> append(r.joinToString(";") { (it ?: "").toString().replace(';', ',') }).append("\r\n") }
        }
        File(dir, name).writeText(text, Charsets.UTF_8)
    }

    fun writeAll(dir: File) {
        dir.mkdirs()
        write(
            dir, "trajets.csv",
            listOf("Date", "Départ", "Arrivée", "Distance (km)", "Roulage (min)", "Moyenne (km/h)", "Maxi (km/h)",
                "Puissance maxi (ch)", "Gazole estimé (L)", "Conso (L/100 km)", "Score", "Accélérations fortes",
                "Freinages forts", "Passages de vitesse", "Bons passages (%)", "Minutes à 70 km/h ou plus"),
            TripLog.history.value.reversed().map { t ->
                listOf(
                    dateFmt.format(Date(t.startMs)), timeFmt.format(Date(t.startMs)), timeFmt.format(Date(t.endMs)),
                    n(t.distanceKm), n(t.movingS / 60, 0), n(t.avgKmh, 0), t.maxKmh.toInt(), t.peakCh.takeIf { it > 0 },
                    n(t.fuelL, 2), n(t.l100), t.score, t.harshAccel, t.harshBrake, t.shifts.takeIf { it >= 0 },
                    t.goodShiftPct.takeIf { t.shifts > 0 }, t.fastS.takeIf { it >= 0 }?.let { n(it / 60.0, 0) },
                )
            },
        )
        write(
            dir, "pleins.csv",
            listOf("Date", "Kilométrage", "Litres", "Prix (€)", "Prix du litre (€)", "Plein complet"),
            Fuel.entries.value.sortedBy { it.date }.map { f ->
                listOf(
                    "%02d/%02d/%d".format(f.date.dayOfMonth, f.date.monthValue, f.date.year), f.km, n(f.liters, 2),
                    n(f.price, 2), n(f.pricePerL, 3), if (f.full) "oui" else "non",
                )
            },
        )
        write(
            dir, "demarrages.csv",
            listOf("Date", "Heure", "Tension au repos (V)", "Tension mini au démarreur (V)", "Tension de charge (V)",
                "Eau (°C)", "Verdict"),
            CrankWatch.history.value.reversed().map { r ->
                listOf(dateFmt.format(Date(r.atMs)), timeFmt.format(Date(r.atMs)), n(r.restV, 2), n(r.minV, 2),
                    n(r.chargeV, 2), r.coolantC, r.verdict.label)
            },
        )
        write(
            dir, "historique.csv",
            listOf("Date", "Kilométrage", "Intervention", "Détail", "Où", "Prix (€)"),
            fr.p308.lanceur.maintenance.History.entries.value.reversed().map { h ->
                listOf(
                    h.date?.let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: h.dateNote,
                    h.km, h.title, h.details, h.place, n(h.priceEur, 2),
                )
            },
        )
        write(
            dir, "chauffes.csv",
            listOf("Date", "Heure", "Eau au départ (°C)", "Extérieur (°C)", "Durée eau à 80 °C (min)",
                "Durée huile (min)", "Part au ralenti (%)", "Vitesse moyenne (km/h)"),
            Warmup.history.value.reversed().map { w ->
                listOf(dateFmt.format(Date(w.startMs)), timeFmt.format(Date(w.startMs)), w.startC, n(w.outsideC, 0),
                    n(w.minutes), n(w.oilMinutes), w.idlePct, w.avgKmh)
            },
        )
        write(
            dir, "regenerations-fap.csv",
            listOf("Date", "Début", "Fin", "Durée (min)", "Kilométrage", "Interrompue"),
            fr.p308.lanceur.vehicle.FapRegen.state.value.records.reversed().map { r ->
                listOf(dateFmt.format(Date(r.startMs)), timeFmt.format(Date(r.startMs)), timeFmt.format(Date(r.endMs)),
                    r.minutes, r.km, if (r.interrupted) "oui" else "non")
            },
        )
    }
}
