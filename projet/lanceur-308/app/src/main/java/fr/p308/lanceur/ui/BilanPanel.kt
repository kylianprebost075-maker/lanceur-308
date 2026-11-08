package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.drive.FapAdvice
import fr.p308.lanceur.drive.FuelModel
import fr.p308.lanceur.drive.MonthReport
import fr.p308.lanceur.drive.MonthStats
import fr.p308.lanceur.drive.TripLog
import fr.p308.lanceur.maintenance.Fuel
import fr.p308.lanceur.vehicle.Vehicle
import fr.p308.lanceur.vehicle.Warmup
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)

private fun f1(v: Double) = "%.1f".format(Locale.FRANCE, v)
private fun f0(v: Double) = "%.0f".format(Locale.FRANCE, v)

/** Onglet Moteur → Bilan : mois en cours comparé au précédent, consommation estimée, état probable du FAP. */
@Composable
fun BilanPanel() {
    val storedTrips by TripLog.history.collectAsStateWithLifecycle()
    val storedWarm by Warmup.history.collectAsStateWithLifecycle()
    val fills by Fuel.entries.collectAsStateWithLifecycle()
    val demo = Vehicle.demo
    val trips = if (demo && storedTrips.isEmpty()) TripLog.demoHistory() else storedTrips
    val warm = if (demo && storedWarm.isEmpty()) Warmup.demoHistory() else storedWarm
    val now = YearMonth.now()
    val cur = MonthReport.compute(now, trips, warm, fills)
    val prev = MonthReport.compute(now.minusMonths(1), trips, warm, fills)
    val price = MonthReport.lastPricePerL(fills)
    val fap = FapAdvice.evaluate(trips)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1.25f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Bilan de ${now.format(MONTH_FMT)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("vs ${now.minusMonths(1).format(MONTH_FMT)}", fontSize = 12.sp, color = C.Sub)
                }
                Spacer(Modifier.height(8.dp))
                Line("Kilomètres", "${f0(cur.km)} km", "${f0(prev.km)} km", cur.km - prev.km, higherIsBetter = null)
                Line("Trajets", "${cur.trips}", "${prev.trips}", (cur.trips - prev.trips).toDouble(), higherIsBetter = null)
                Line("Temps au volant", "${f1(cur.drivingH)} h", "${f1(prev.drivingH)} h", cur.drivingH - prev.drivingH, higherIsBetter = null)
                Line("Gazole estimé", cur.fuelL?.let { "${f1(it)} L" } ?: "—", prev.fuelL?.let { "${f1(it)} L" } ?: "—",
                    diff(cur.fuelL, prev.fuelL), higherIsBetter = false)
                if (price != null) {
                    Line("Coût estimé", cur.fuelL?.let { "${f0(it * price)} €" } ?: "—", prev.fuelL?.let { "${f0(it * price)} €" } ?: "—",
                        diff(cur.fuelL, prev.fuelL), higherIsBetter = false)
                }
                Line("Consommation", cur.avgL100?.let { "${f1(it)} L/100" } ?: "—", prev.avgL100?.let { "${f1(it)} L/100" } ?: "—",
                    diff(cur.avgL100, prev.avgL100), higherIsBetter = false)
                Line("Pleins payés", "${f0(cur.fillSpend)} €", "${f0(prev.fillSpend)} €", cur.fillSpend - prev.fillSpend, higherIsBetter = null)
                Line("Score moyen", cur.avgScore?.toString() ?: "—", prev.avgScore?.toString() ?: "—",
                    diff(cur.avgScore?.toDouble(), prev.avgScore?.toDouble()), higherIsBetter = true)
                Line("Chauffe moyenne", cur.avgWarmMin?.let { "${it.toInt()} min" } ?: "—", prev.avgWarmMin?.let { "${it.toInt()} min" } ?: "—",
                    diff(cur.avgWarmMin?.toDouble(), prev.avgWarmMin?.toDouble()), higherIsBetter = false)
                Spacer(Modifier.height(6.dp))
                Text(summary(cur, prev), fontSize = 13.sp, color = C.Sub)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Modifier.weight(1f).fillMaxWidth()) {
                Text("Filtre à particules", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (fap == null) {
                    Text("Pas encore de trajet enregistré.", fontSize = 14.sp, color = C.Sub)
                } else {
                    Text(
                        when (fap.level) { 2 -> "Voie rapide à faire vite"; 1 -> "Voie rapide conseillée"; else -> "RAS" },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = when (fap.level) { 2 -> C.Red; 1 -> C.Orange; else -> C.Green },
                    )
                    Text(fap.text, fontSize = 13.sp)
                    Text("Depuis la dernière voie rapide : ${fap.tripsSince} trajet(s), ${f0(fap.kmSince)} km.",
                        fontSize = 12.sp, color = C.Sub)
                }
                Spacer(Modifier.weight(1f))
                Text("Déduit de tes trajets : le FAP n'est pas lisible sur la prise OBD.", fontSize = 11.sp, color = C.Sub)
            }
            Tile(Modifier.weight(1f).fillMaxWidth()) {
                Text("Consommation", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val st = Fuel.stats(fills)
                Spacer(Modifier.height(4.dp))
                Text("Estimée ce mois : ${cur.avgL100?.let { "${f1(it)} L/100" } ?: "—"}", fontSize = 15.sp)
                Text("Mesurée aux pleins : ${st.lastL100?.let { "%.1f L/100".format(Locale.FRANCE, it) } ?: "il faut 2 pleins complets"}",
                    fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (FuelModel.samples == 0) "Pas encore corrigée par tes pleins : saisis tes pleins complets (Entretien → Pleins), " +
                        "elle devient plus juste à chaque plein."
                    else "Corrigée par ${FuelModel.samples} intervalle(s) entre pleins (facteur ${"%.2f".format(Locale.FRANCE, FuelModel.factor)}).",
                    fontSize = 12.sp, color = C.Sub,
                )
                Spacer(Modifier.weight(1f))
                Text("Estimée à partir de la puissance demandée (OBD).", fontSize = 11.sp, color = C.Sub)
            }
        }
    }
}

private fun diff(a: Double?, b: Double?): Double? = if (a != null && b != null) a - b else null

/** Une ligne : libellé, mois en cours, mois précédent, flèche colorée (vert = mieux, orange = moins bien). */
@Composable
private fun Line(label: String, cur: String, prev: String, delta: Double?, higherIsBetter: Boolean?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(C.Field.copy(0.25f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = C.Sub, modifier = Modifier.weight(1.2f))
        Text(cur, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        val arrow = when {
            delta == null || kotlin.math.abs(delta) < 1e-6 -> ""
            delta > 0 -> "▲"
            else -> "▼"
        }
        val color = when {
            arrow.isEmpty() || higherIsBetter == null -> C.Sub
            (delta!! > 0) == higherIsBetter -> C.Green
            else -> C.Orange
        }
        Text(arrow, fontSize = 13.sp, color = color, modifier = Modifier.padding(end = 6.dp))
        Text(prev, fontSize = 13.sp, color = C.Sub, modifier = Modifier.weight(0.9f))
    }
}

private fun summary(cur: MonthStats, prev: MonthStats): String {
    if (cur.trips == 0) return "Aucun trajet ce mois-ci pour l'instant."
    val parts = mutableListOf<String>()
    if (cur.avgScore != null && prev.avgScore != null) {
        val d = cur.avgScore - prev.avgScore
        parts += if (d >= 3) "conduite plus souple que le mois dernier (+$d)" else if (d <= -3) "conduite moins souple ($d)" else "conduite aussi souple"
    }
    if (cur.avgL100 != null && prev.avgL100 != null) {
        val d = cur.avgL100 - prev.avgL100
        if (kotlin.math.abs(d) >= 0.2) parts += "consommation ${if (d < 0) "en baisse" else "en hausse"} de ${f1(kotlin.math.abs(d))} L/100"
    }
    return if (parts.isEmpty()) "${cur.trips} trajet(s), ${f0(cur.km)} km ce mois-ci." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}
