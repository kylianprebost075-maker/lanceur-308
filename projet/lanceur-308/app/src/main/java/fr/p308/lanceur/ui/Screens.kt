package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.drive.Trip
import fr.p308.lanceur.maintenance.DueLevel
import fr.p308.lanceur.maintenance.MaintenanceItem
import fr.p308.lanceur.maintenance.due
import fr.p308.lanceur.maintenance.fmtKm
import fr.p308.lanceur.maintenance.intervalLabel
import fr.p308.lanceur.maintenance.label
import fr.p308.lanceur.vehicle.BatteryLevel
import fr.p308.lanceur.vehicle.BatteryWatch
import fr.p308.lanceur.vehicle.Engine
import fr.p308.lanceur.vehicle.Vehicle
import fr.p308.lanceur.vehicle.VehicleState
import fr.p308.lanceur.vehicle.describeOpen
import fr.p308.lanceur.vehicle.openingsFor
import fr.p308.lanceur.vehicle.odometerShortOrigin
import fr.p308.lanceur.vehicle.odometerText
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_FR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val CLOCK_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

fun VehicleState.visibleOpen() = open.filter { it in openingsFor(UiPrefs.fiveDoors) }.toSet()

/** Phares effectifs : valeur forcée/CAN si présente, sinon allumage automatique dès la tombée de la nuit. */
fun VehicleState.effectiveHeadlights(): Boolean = headlights ?: Night.active

/** Bascule rapide 1 touche : alterne entre Allumés / Éteints tout en revenant au mode Auto au second appui. */
private fun toggleHeadlights() {
    Vehicle.update { s ->
        s.copy(headlights = if (s.headlights == null) !Night.active else null)
    }
}

private fun levelColor(level: DueLevel) = when (level) {
    DueLevel.LATE -> C.Red
    DueLevel.SOON -> C.Orange
    DueLevel.OK -> C.Green
    DueLevel.NOT_SET -> C.Sub
}

@Composable
private fun StatusLine(vs: VehicleState) {
    val open = vs.visibleOpen()
    val lightsOn = vs.effectiveHeadlights()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (open.isEmpty()) C.Green else C.Red))
        Spacer(Modifier.width(6.dp))
        Text(describeOpen(open), color = if (open.isEmpty()) C.Sub else C.Red, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (lightsOn) {
            Spacer(Modifier.width(10.dp))
            Text("· Phares allumés", color = Color(0xFFFFD54F), fontSize = 14.sp, maxLines = 1)
        }
        if (vs.fogLights) {
            Spacer(Modifier.width(10.dp))
            Text("· Antibrouillards", color = C.Orange, fontSize = 14.sp, maxLines = 1)
        }
        val battery by BatteryWatch.state.collectAsStateWithLifecycle()
        battery.message?.let {
            Spacer(Modifier.width(10.dp))
            Text("· ${it.substringBefore(" (")}", color = if (battery.level == BatteryLevel.LOW) C.Orange else C.Red, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** Pastille de vitesse isolée : seule cette petite pastille se redessine quand la vitesse évolue en roulant. */
@Composable
private fun LiveSpeedBadge() {
    val trip by Trip.state.collectAsStateWithLifecycle()
    val kmh = trip.currentKmh
    if (kmh < 2f) return
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(C.Accent.copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(C.Green))
        Spacer(Modifier.width(6.dp))
        Text("${kmh.roundToInt()} km/h", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Text)
    }
}

@Composable
private fun Chip(icon: ImageVector, title: String, value: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(C.Field.copy(0.6f)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 12.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = C.Sub)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpinArrow(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(C.Field.copy(alpha = 0.65f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 20.sp, color = C.Text) }
}

/**
 * Écran unifié Accueil + Entretien façon Tesla :
 * Les proportions (1.48 / 1.0) sont fixes pour ne JAMAIS redimensionner la SurfaceView OpenGL 3D
 * lors du passage entre Accueil et Entretien (zéro saccade sur l'autoradio).
 */
@Composable
fun HomeAndMaintenanceScreen(vs: VehicleState, items: List<MaintenanceItem>, interior: Boolean, onTab: (Tab) -> Unit) {
    val dues = remember(items, vs.odometerKm) { items.map { it.due(vs.odometerKm) } }
    val urgent = remember(dues) { dues.count { it.level == DueLevel.SOON || it.level == DueLevel.LATE } }
    val worst = remember(dues) { dues.maxOfOrNull { it.level } ?: DueLevel.NOT_SET }
    val open = vs.visibleOpen()
    val lightsOn = vs.effectiveHeadlights()
    val ctx = LocalContext.current
    var model3d by remember { mutableStateOf(CarModel.location(ctx)) }
    val spinCtrl = remember { CarSpinController() }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(
            modifier = Modifier.weight(1.48f).fillMaxHeight(),
            background = SolidColor(C.Card),
            onClick = if (interior) null else ({ onTab(Tab.VEHICLE) }),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!interior) {
                    Column(Modifier.weight(1f)) {
                        Text("Peugeot 308", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        StatusLine(vs)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LiveSpeedBadge()
                        if (model3d != null) {
                            SpinArrow("‹") { spinCtrl.turn(-45f) }
                            SpinArrow("›") { spinCtrl.turn(45f) }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text("Habitacle 308", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Glisse le doigt pour regarder autour", fontSize = 13.sp, color = C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "← Extérieur", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(C.Accent)
                            .clickable { onTab(Tab.HOME) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }

            val carColor = Color(UiPrefs.carColor.argb)
            Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val location = model3d
                if (location != null) {
                    Car3D(
                        location = location,
                        interior = interior,
                        open = open,
                        headlights = lightsOn,
                        fogLights = vs.fogLights,
                        reverse = vs.reverse,
                        spinController = spinCtrl,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onFail = { model3d = null },
                    )
                    if (!interior && vs.rearRadarCm.isNotEmpty()) {
                        CarTopView(open, carColor, UiPrefs.fiveDoors, vs.rearRadarCm, horizontal = false,
                            modifier = Modifier.fillMaxHeight().width(106.dp)
                                .clip(RoundedCornerShape(14.dp)).background(C.Bg.copy(alpha = 0.6f)).padding(6.dp))
                    }
                } else {
                    CarTopView(open, carColor, UiPrefs.fiveDoors, vs.rearRadarCm, horizontal = true, modifier = Modifier.fillMaxSize())
                }
            }

            val lightsSubtitle = when (vs.headlights) {
                true -> "Allumés"
                false -> "Éteints"
                null -> if (lightsOn) "Auto (nuit)" else "Auto (jour)"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(Icons.Filled.MeetingRoom, "Ouvrants", if (open.isEmpty()) "Fermés" else "${open.size} ouvert(s)",
                    if (open.isEmpty()) C.Green else C.Red, Modifier.weight(1f)) { onTab(Tab.VEHICLE) }
                Chip(Icons.Filled.Highlight, "Phares", lightsSubtitle,
                    if (lightsOn) Color(0xFFFFD54F) else C.Sub, Modifier.weight(1f)) {
                    toggleHeadlights()
                }
                if (!interior) {
                    Chip(Icons.Filled.Build, "Entretien", if (urgent == 0) "À jour" else "$urgent à prévoir",
                        if (urgent == 0) C.Green else levelColor(worst), Modifier.weight(1f)) { onTab(Tab.MAINTENANCE) }
                    Chip(Icons.Filled.Sensors, "Radars", if (vs.reverse) "Actifs" else "En veille",
                        if (vs.reverse) C.Orange else C.Sub, Modifier.weight(1f)) { onTab(Tab.VEHICLE) }
                }
            }
        }

        Box(Modifier.weight(1.0f).fillMaxHeight()) {
            if (interior) {
                MaintenanceScreen(vs, items)
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClockTile(Modifier.weight(1f).fillMaxWidth())
                    Tile(Modifier.weight(1f).fillMaxWidth()) {
                        Row(Modifier.weight(1f)) {
                            Metric("Extérieur", vs.outsideTempC?.let { if (vs.outsideTempEstimated) "≈ %.0f °C".format(Locale.FRANCE, it) else "%.1f °C".format(Locale.FRANCE, it) } ?: "—", Modifier.weight(1f))
                            BatteryMetric(Modifier.weight(1f))
                        }
                        Row(Modifier.weight(1f)) {
                            Metric("Autonomie", vs.fuelRangeKm?.let { "$it km" } ?: "—", Modifier.weight(1f))
                            Metric("Kilométrage", vs.odometerText() ?: "—", Modifier.weight(1f))
                        }
                        EngineMetrics(Modifier.weight(1f).clickable { onTab(Tab.ENGINE) })
                        Text("Données : ${vs.source}", fontSize = 11.sp, color = C.Sub)
                    }
                }
            }
        }
    }
}

/** Tension batterie (CAN ou OBD), en orange ou rouge si l'alerte est active. */
@Composable
private fun BatteryMetric(modifier: Modifier) {
    val b by BatteryWatch.state.collectAsStateWithLifecycle()
    Column(modifier) {
        Text("Batterie", fontSize = 12.sp, color = C.Sub)
        Text(
            b.volts?.let { "%.1f V".format(Locale.FRANCE, it) } ?: "—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = when (b.level) { BatteryLevel.LOW -> C.Orange; BatteryLevel.CRITICAL, BatteryLevel.NOT_CHARGING -> C.Red; else -> C.Text },
        )
    }
}

/** Puissance et régime : lit l'état moteur ici pour que le reste de l'accueil (et la 3D) ne se redessine pas. */
@Composable
private fun EngineMetrics(modifier: Modifier) {
    val engine by Engine.state.collectAsStateWithLifecycle()
    Row(modifier) {
        Metric("Puissance", engine.powerCh?.let { "$it ch" } ?: "—", Modifier.weight(1f))
        Metric("Régime", engine.rpm?.let { "${fmtKm(it)} tr/min" } ?: "—", Modifier.weight(1f))
    }
}

/** Horloge alignée sur le changement de minute (se réveille 1 fois/min au lieu de chaque seconde). */
@Composable
private fun ClockTile(modifier: Modifier) {
    val now by produceState(fr.p308.lanceur.vehicle.GpsClock.localNow()) {
        while (true) {
            // Heure remise à l'heure par le GPS (voir GpsClock).
            val cur = fr.p308.lanceur.vehicle.GpsClock.localNow()
            value = cur
            val waitMs = (60 - cur.second) * 1_000L - (cur.nano / 1_000_000L) + 50L
            delay(waitMs.coerceIn(500L, 60_050L))
        }
    }
    Tile(modifier) {
        Spacer(Modifier.weight(1f))
        Text(now.format(CLOCK_FMT), fontSize = 64.sp, fontWeight = FontWeight.Light)
        Text(now.format(DAY_FMT).replaceFirstChar { it.uppercase() }, fontSize = 16.sp, color = C.Sub)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun VehicleScreen(vs: VehicleState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented(listOf(0 to "État", 1 to "GPS", 2 to "Diagnostic CAN"), tab) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                1 -> GpsPanel()
                2 -> CanProbePanel()
                else -> VehicleStatePanel(vs)
            }
        }
    }
}

@Composable
private fun VehicleStatePanel(vs: VehicleState) {
    val open = vs.visibleOpen()
    val lightsOn = vs.effectiveHeadlights()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Text("Véhicule", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            StatusLine(vs)
            CarTopView(open, Color(UiPrefs.carColor.argb), UiPrefs.fiveDoors, vs.rearRadarCm, horizontal = false,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp))
        }
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Ouvrants", fontSize = 13.sp, color = C.Sub)
                openingsFor(UiPrefs.fiveDoors).forEach { o ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(o.label, Modifier.weight(1f), fontSize = 15.sp)
                        val isOpen = o in open
                        Text(if (isOpen) (if (o.feminine) "Ouverte" else "Ouvert") else (if (o.feminine) "Fermée" else "Fermé"),
                            color = if (isOpen) C.Red else C.Green, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Éclairage (automatique au coucher du soleil ou manuel)", fontSize = 13.sp, color = C.Sub)
                Spacer(Modifier.height(6.dp))
                Segmented(
                    options = listOf(null to "Auto (soleil)", true to "Allumés", false to "Éteints"),
                    selected = vs.headlights,
                ) { mode ->
                    Vehicle.update { s -> s.copy(headlights = mode) }
                }
                Text(
                    if (lightsOn) "État actuel : Phares allumés" else "État actuel : Phares éteints",
                    fontSize = 13.sp,
                    color = if (lightsOn) Color(0xFFFFD54F) else C.Sub,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                Row(Modifier.fillMaxWidth().clickable { Vehicle.update { s -> s.copy(fogLights = !s.fogLights) } }.padding(vertical = 5.dp)) {
                    Text("Antibrouillards", Modifier.weight(1f), fontSize = 16.sp)
                    Text(if (vs.fogLights) "Allumés" else "Éteints", color = if (vs.fogLights) C.Orange else C.Sub, fontSize = 16.sp)
                }

                Spacer(Modifier.height(10.dp))
                Text("Radars de recul", fontSize = 13.sp, color = C.Sub)
                if (vs.rearRadarCm.isEmpty()) {
                    Text(if (vs.reverse) "Marche arrière : aucune donnée radar" else "En veille (s'active en marche arrière)",
                        fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("G", "CG", "CD", "D").zip(vs.rearRadarCm).forEach { (name, cm) ->
                            Metric(name, cm?.let { "$it cm" } ?: "—")
                        }
                    }
                    vs.rearRadarCm.filterNotNull().minOrNull()?.let {
                        Text("Distance mini : $it cm", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            color = if (it <= 40) C.Red else if (it <= 90) C.Orange else C.Green)
                    }
                }
            }
        }
    }
}

@Composable
fun MaintenanceScreen(vs: VehicleState, items: List<MaintenanceItem>) {
    var page by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented(listOf(0 to "Entretien", 1 to "Pleins", 2 to "Historique"), page) { page = it }
        Box(Modifier.weight(1f)) {
            when (page) {
                1 -> FuelPanel(vs.odometerKm)
                2 -> HistoryPanel()
                else -> MaintenancePanel(vs, items)
            }
        }
    }
}

@Composable
private fun MaintenancePanel(vs: VehicleState, items: List<MaintenanceItem>) {
    var editing by remember { mutableStateOf<MaintenanceItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingKm by remember { mutableStateOf(false) }

    Tile(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Entretien", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Peugeot 308 · 1.6 HDi 110", fontSize = 13.sp, color = C.Accent)
            }
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { editingKm = true }.padding(8.dp), horizontalAlignment = Alignment.End) {
                Text("Kilométrage", fontSize = 12.sp, color = C.Sub)
                Text(vs.odometerText() ?: "Toucher pour saisir", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                vs.odometerShortOrigin()?.let { Text(it, fontSize = 10.sp, color = C.Sub, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Contrôle technique sans date : sans elle, aucun rappel possible.
        items.firstOrNull { fr.p308.lanceur.maintenance.Maintenance.isCt(it) && it.lastDate == null }?.let { ct ->
            Text(
                "📅  Touche ici pour mettre la date de ton dernier contrôle technique : rappel 30 jours puis 7 jours avant.",
                fontSize = 14.sp, color = C.Orange,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Orange.copy(0.12f))
                    .clickable { editing = ct }.padding(10.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { item -> MaintenanceRow(item, vs.odometerKm) { editing = item } }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { adding = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = C.Accent)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Ajouter un entretien", fontWeight = FontWeight.SemiBold)
                        Text("Pneus, amortisseurs, climatisation, batterie…", fontSize = 12.sp, color = C.Sub)
                    }
                }
            }
        }
    }
    editing?.let { MaintenanceDialog(it, vs.odometerKm) { editing = null } }
    if (adding) MaintenanceDialog(null, vs.odometerKm) { adding = false }
    if (editingKm) OdometerDialog(vs.odometerKm) { editingKm = false }
}

@Composable
private fun MaintenanceRow(item: MaintenanceItem, km: Int?, onClick: () -> Unit) {
    val due = item.due(km)
    val color = levelColor(due.level)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Field.copy(0.45f)).clickable(onClick = onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val done = when {
                    item.lastDate != null && item.lastKm != null -> "Fait le ${item.lastDate.format(DATE_FR)} à ${fmtKm(item.lastKm)} km"
                    item.lastDate != null -> "Fait le ${item.lastDate.format(DATE_FR)}"
                    item.lastKm != null -> "Fait à ${fmtKm(item.lastKm)} km"
                    else -> "Pas encore renseigné"
                }
                Text("$done · ${item.intervalLabel()}", fontSize = 12.sp, color = C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(due.label(), color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        // Vidange : avancée selon l'usage (petits trajets), avec l'explication.
        val wear = fr.p308.lanceur.maintenance.OilWear.info
        if (fr.p308.lanceur.maintenance.OilWear.isOil(item) && wear != null && item.lastKm != null && km != null) {
            val driven = (km - item.lastKm).coerceAtLeast(0)
            val ahead = fr.p308.lanceur.maintenance.OilWear.effectiveKm(driven) - driven
            Text(
                if (wear.shortPct >= 20) "Usage sévère : ${wear.shortPct} % de petits trajets → vidange avancée d'environ ${fmtKm(ahead)} km"
                else "Usage normal (${wear.shortPct} % de petits trajets) : intervalle habituel",
                fontSize = 11.sp, color = if (wear.shortPct >= 20) C.Orange else C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (due.level != DueLevel.NOT_SET) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { due.progress }, color = color, trackColor = C.Bg,
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}
