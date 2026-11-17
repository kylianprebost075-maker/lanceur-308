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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import fr.p308.lanceur.vehicle.BatteryWatch
import fr.p308.lanceur.vehicle.CrankRecord
import fr.p308.lanceur.vehicle.CrankVerdict
import fr.p308.lanceur.vehicle.CrankWatch
import fr.p308.lanceur.vehicle.Engine
import fr.p308.lanceur.vehicle.Vehicle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun volts(v: Float) = "%.1f V".format(Locale.FRANCE, v)

private fun verdictColor(v: CrankVerdict): Color = when (v) {
    CrankVerdict.GOOD -> C.Green
    CrankVerdict.FAIR -> C.Orange
    CrankVerdict.WEAK -> C.Red
}

/** Gris quand la chute au démarreur n'a pas été lue et que le repos est correct : ni bon ni mauvais (v0.42). */
private fun crankColor(r: CrankRecord): Color = if (!r.measured && r.verdict == CrankVerdict.GOOD) C.Sub else verdictColor(r.verdict)

/** Onglet Moteur → Batterie : tension actuelle, santé mesurée à chaque démarrage, tendance. */
@Composable
fun BatteryPanel() {
    val e by Engine.state.collectAsStateWithLifecycle()
    val b by BatteryWatch.state.collectAsStateWithLifecycle()
    val vs by Vehicle.state.collectAsStateWithLifecycle()
    val stored by CrankWatch.history.collectAsStateWithLifecycle()
    val demo = Vehicle.demo
    val list = if (demo && stored.isEmpty()) CrankWatch.demoHistory() else stored
    val last = list.firstOrNull()
    val trend = CrankWatch.trend(list)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Text("Batterie", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (demo) "Démo : exemple de démarrages" else if (e.connected) "Boîtier OBD connecté"
                else if (vs.batteryV != null) "Tension lue par l'autoradio" else e.status,
                fontSize = 13.sp, color = if (e.connected) C.Green else C.Sub, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Text("Tension maintenant", fontSize = 12.sp, color = C.Sub)
            Text((b.volts ?: e.voltage)?.let(::volts) ?: "—", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            if (vs.batteryV != null && !demo) Text("Lue sur l'autoradio (boîtier CAN), sans boîtier OBD", fontSize = 11.sp, color = C.Sub)
            b.message?.let { Text(it, fontSize = 13.sp, color = C.Orange) }
            b.engineOffMin?.takeIf { it >= 1 && !demo }?.let { min ->
                Text("Moteur coupé depuis $min min, autoradio allumé" + if (min >= BatteryWatch.DRAIN_STEP_MIN) " : la batterie se vide" else "",
                    fontSize = 13.sp, color = if (min >= BatteryWatch.DRAIN_STEP_MIN) C.Orange else C.Sub)
            }
            Spacer(Modifier.height(10.dp))
            // Moteur coupé et boîtier prêt : on le dit, pour que l'utilisateur sache qu'il peut démarrer.
            if (!demo && e.connected && (e.rpm ?: 0) <= 400) {
                val ready = CrankWatch.ready()
                Text(
                    if (ready) "✓ Prêt : démarre, la mesure se fait toute seule" else "Mesure en préparation…",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (ready) C.Green else C.Orange,
                )
                Spacer(Modifier.height(6.dp))
            }
            // v0.42 : le verdict affiché est celui du dernier démarrage MESURÉ (chute au démarreur lue). Un démarrage
            // noté sans la chute (autoradio allumé trop tard, relevé du CAN) ne permet pas de dire « bonne ».
            val shown = CrankWatch.forVerdict(list)
            val dayFmt = SimpleDateFormat("EEE d MMM à HH:mm", Locale.FRANCE)
            Text(if (shown != null && last != null && shown.atMs != last.atMs) "Dernier démarrage mesuré (${dayFmt.format(Date(shown.atMs))})"
                else "Dernier démarrage", fontSize = 12.sp, color = C.Sub)
            if (shown == null) {
                Text("Pas encore mesuré", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(shown.verdictLabel, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = crankColor(shown))
                Text("Repos ${volts(shown.restV)} · ${shown.crankText}", fontSize = 15.sp)
                shown.chargeV?.let { Text("Charge ${volts(it)} : ${CrankWatch.chargeText(it)}", fontSize = 15.sp) }
                Text(when {
                    !shown.measured && shown.verdict == CrankVerdict.GOOD ->
                        "Tension au repos correcte, mais la vraie chute au démarreur n'a pas été lue : ce démarrage ne dit " +
                            "pas si la batterie tient. Mets le contact, attends « ✓ Prêt » (boîtier OBD), puis démarre."
                    shown.verdict == CrankVerdict.GOOD -> "La batterie tient bien la charge du démarreur."
                    shown.verdict == CrankVerdict.FAIR -> "Elle faiblit : surveille, surtout quand il fait froid."
                    else -> "Fais-la tester (contrôle gratuit chez la plupart des centres auto)."
                }, fontSize = 13.sp, color = C.Sub)
                if (last != null && shown.atMs != last.atMs) {
                    Text("Démarrage suivant (${dayFmt.format(Date(last.atMs))}) : repos ${volts(last.restV)}, ${last.crankText}.",
                        fontSize = 12.sp, color = C.Sub)
                }
            }
            // Santé dans le temps (tous les démarrages, froid pris en compte), sinon la simple tendance.
            val health = fr.p308.lanceur.maintenance.BatteryHealthModel.evaluate(list)
            if (health != null) {
                Spacer(Modifier.height(8.dp))
                Text("Santé dans le temps", fontSize = 12.sp, color = C.Sub)
                Text(health.title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = when (health.level) { 0 -> C.Green; 1 -> C.Orange; else -> C.Red })
                Text(health.text, fontSize = 13.sp, color = C.Sub)
            } else trend?.let {
                Spacer(Modifier.height(6.dp))
                val txt = when {
                    it <= -0.3f -> "Tendance : en baisse (%.1f V sur les 10 derniers démarrages)".format(Locale.FRANCE, it)
                    it >= 0.3f -> "Tendance : en hausse (+%.1f V)".format(Locale.FRANCE, it)
                    else -> "Tendance : stable"
                }
                Text(txt, fontSize = 13.sp, color = if (it <= -0.3f) C.Orange else C.Sub)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Mesuré à chaque démarrage : mets le contact, attends « ✓ Prêt », puis démarre. Si la connexion " +
                    "coupe pendant le démarreur, la tension au repos et la charge sont quand même notées.",
                fontSize = 11.sp, color = C.Sub,
            )
        }
        Tile(Modifier.weight(1.3f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth()) {
                Text("Derniers démarrages", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("repos · mini", fontSize = 12.sp, color = C.Sub)
            }
            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Aucun démarrage mesuré pour l'instant.", color = C.Sub, fontSize = 15.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.atMs }) { r -> CrankRow(r) }
                }
            }
            Text("Repères : au démarreur, plus de 10,2 V = bonne ; 9,6 à 10,2 V = à surveiller ; moins de 9,6 V = faible.",
                fontSize = 11.sp, color = C.Sub)
        }
    }
}

@Composable
private fun CrankRow(r: CrankRecord) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field.copy(0.5f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(crankColor(r)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(SimpleDateFormat("EEE d MMM · HH:mm", Locale.FRANCE).format(Date(r.atMs)), fontSize = 14.sp)
            val temp = r.coolantC?.let { if (it < 30) "moteur froid ($it °C)" else "moteur chaud ($it °C)" }
            val how = if (!r.measured) "chute non lue" + (if (r.source == "CAN") " (relevé de l'autoradio)" else "") else null
            listOfNotNull(temp, how).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), fontSize = 11.sp, color = C.Sub) }
        }
        Text("${volts(r.restV)} · ${r.minV?.let(::volts) ?: "—"}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
