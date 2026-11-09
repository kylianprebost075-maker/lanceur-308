package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import fr.p308.lanceur.vehicle.Engine
import fr.p308.lanceur.vehicle.Vehicle
import fr.p308.lanceur.vehicle.WarmRecord
import fr.p308.lanceur.vehicle.Warmup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun mins(m: Float): String = if (m < 1f) "moins d'1 min" else "${m.roundToInt()} min"

private fun oilColor(c: Float?): Color = when {
    c == null -> Color.White
    c < 60f -> Color(0xFF64B5F6)
    c >= 125f -> C.Red
    else -> Color.White
}

/** Onglet Moteur → Chauffe : huile estimée, temps de chauffe prévu d'après les chauffes passées. */
@Composable
fun WarmupPanel() {
    val w by Warmup.state.collectAsStateWithLifecycle()
    val e by Engine.state.collectAsStateWithLifecycle()
    val vs by Vehicle.state.collectAsStateWithLifecycle()
    val stored by Warmup.history.collectAsStateWithLifecycle()
    val demo = Vehicle.demo
    val list = if (demo && stored.isEmpty()) Warmup.demoHistory() else stored

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Text("Chauffe du moteur", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1.2f)) {
                    Text("Huile (estimée)", fontSize = 12.sp, color = C.Sub)
                    Text(w.oilC?.let { "≈ ${it.roundToInt()} °C" } ?: "—", fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                        color = oilColor(w.oilC))
                }
                Column(Modifier.weight(1f)) {
                    Text("Eau", fontSize = 12.sp, color = C.Sub)
                    Text((w.coolantC ?: e.coolantC)?.let { "$it °C" } ?: "—", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Extérieur", fontSize = 12.sp, color = C.Sub)
                    Text(vs.outsideTempC?.let { "${if (vs.outsideTempEstimated) "≈ " else ""}${it.roundToInt()} °C" } ?: "—",
                        fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            val start = w.startC
            val cool = w.coolantC
            if (w.active && start != null && cool != null) {
                val f = ((cool - start).toFloat() / (Warmup.TARGET_C - start)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { f }, color = if (f >= 1f) C.Green else C.Orange, trackColor = C.Bg,
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                )
                Spacer(Modifier.height(8.dp))
            }
            val (main, sub) = when {
                w.running && w.active -> {
                    val r = w.remainingMin ?: 0f
                    (if (r <= 0f) "Eau chaude (80 °C)" else "Moteur chaud dans ≈ ${mins(r)}") to
                        listOfNotNull(
                            w.oilRemainingMin?.takeIf { it > 0f }?.let { "huile chaude dans ≈ ${mins(it)}" },
                            "chauffe en cours depuis ${w.elapsedS / 60} min, ${w.basis}",
                        ).joinToString(" · ")
                }
                w.running -> {
                    val o = w.oilRemainingMin
                    if (o != null && o > 0f) "Eau chaude, huile chaude dans ≈ ${mins(o)}" to "estimation d'après l'eau et la puissance demandée"
                    else "Moteur et huile chauds" to "tu peux utiliser toute la puissance"
                }
                w.nextMin != null -> "Moteur chaud ≈ ${mins(w.nextMin!!)} après le démarrage" to
                    "eau à ${w.nextFromC} °C maintenant · ${w.basis}"
                e.connected -> "Moteur encore chaud" to "pas de chauffe à prévoir"
                else -> "Boîtier OBD non connecté" to "la prévision utilise la température extérieure quand elle est connue"
            }
            Text(main, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (main.startsWith("Moteur et huile") || main.startsWith("Eau chaude (")) C.Green else Color.White)
            Text(sub, fontSize = 13.sp, color = C.Sub)
            w.stalledC?.let { c ->
                Spacer(Modifier.height(6.dp))
                Text("L'eau plafonne à $c °C alors que tu roules depuis 10 min : le thermostat reste peut-être ouvert " +
                    "(panne courante, le moteur chauffe mal et consomme plus). Si l'aiguille du tableau de bord reste basse " +
                    "et que le chauffage est tiède, fais-le vérifier.", fontSize = 13.sp, color = C.Orange)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Pendant la chauffe : roule tout de suite en douceur, sous 2 500 tr/min, plutôt que de laisser tourner au " +
                    "ralenti. Un diesel chauffe très lentement à l'arrêt. Attends l'huile ≈ 60 °C avant d'accélérer fort.",
                fontSize = 12.sp, color = C.Sub,
            )
            Text("Huile ESTIMÉE (la 308 ne la donne pas sur la prise OBD) à partir de l'eau et de la puissance.",
                fontSize = 11.sp, color = C.Sub)
        }
        Tile(Modifier.weight(1.1f).fillMaxHeight()) {
            Text("Tes chauffes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val (driving, idling) = Warmup.styles(list)
            if (driving != null || idling != null) {
                Text(
                    "Pour un départ à 10 °C : " + listOfNotNull(
                        driving?.let { "en roulant tout de suite ≈ ${mins(it.first)} (${it.second} fois)" },
                        idling?.let { "en laissant tourner d'abord ≈ ${mins(it.first)} (${it.second} fois)" },
                    ).joinToString(" · "),
                    fontSize = 13.sp, color = C.Sub,
                )
                if (driving != null && idling != null && idling.first > driving.first + 1f) {
                    Text("Rouler tout de suite chauffe ≈ ${mins(idling.first - driving.first)} plus vite chez toi.",
                        fontSize = 13.sp, color = C.Green)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Aucune chauffe enregistrée : la prochaine partira d'un moteur froid (eau sous 60 °C).",
                        color = C.Sub, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.startMs }) { WarmRow(it) }
                }
            }
        }
    }
}

@Composable
private fun WarmRow(r: WarmRecord) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field.copy(0.5f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(SimpleDateFormat("EEE d MMM · HH:mm", Locale.FRANCE).format(Date(r.startMs)), fontSize = 14.sp)
            Text(
                listOfNotNull(
                    "eau ${r.startC} °C au départ",
                    r.outsideC?.let { "dehors ≈ ${it.roundToInt()} °C" },
                    if (r.idlePct >= 40) "ralenti ${r.idlePct} %" else "roulé tout de suite (${r.avgKmh} km/h)",
                    if (r.stalled) "eau plafonnée" else if (r.abnormal) "anormalement longue, pas comptée" else null,
                ).joinToString(" · "),
                fontSize = 11.sp, color = C.Sub,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(mins(r.minutes), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            r.oilMinutes?.let { Text("huile ${mins(it)}", fontSize = 11.sp, color = C.Sub) }
        }
    }
}
