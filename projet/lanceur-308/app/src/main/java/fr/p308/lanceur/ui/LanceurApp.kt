package fr.p308.lanceur.ui

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.Slot
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.vehicle.GpsMonitor
import kotlinx.coroutines.delay
import fr.p308.lanceur.launchSlot
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.vehicle.Vehicle

enum class Tab { HOME, VEHICLE, ENGINE, MAINTENANCE, SOUND }

@Composable
fun LanceurApp() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showApps by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val vs by Vehicle.state.collectAsStateWithLifecycle()
    val items by Maintenance.items.collectAsStateWithLifecycle()

    // Thème nuit : recalculé chaque minute, et dès que les feux ou le réglage changent.
    LaunchedEffect(UiPrefs.nightMode, vs.headlights) {
        while (true) {
            val g = GpsMonitor.state.value
            Night.refresh(UiPrefs.nightMode, vs.headlights, g.lat, g.lon)
            delay(60_000)
        }
    }
    LaunchedEffect(Night.active, UiPrefs.dimAtNight) {
        (ctx as? Activity)?.window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (Night.active && UiPrefs.dimAtNight) 0.25f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    Row(Modifier.fillMaxSize().appBackground().padding(8.dp)) {
        Column(
            Modifier.width(76.dp).fillMaxHeight()
                .glassSurface(RoundedCornerShape(if (GlassStyle.on) 24.dp else 20.dp), androidx.compose.ui.graphics.SolidColor(C.Card))
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DockButton(Icons.Filled.Home, "Accueil", tab == Tab.HOME) { tab = Tab.HOME }
            DockButton(Icons.Filled.DirectionsCar, "Véhicule", tab == Tab.VEHICLE) { tab = Tab.VEHICLE }
            DockButton(Icons.Filled.Speed, "Moteur", tab == Tab.ENGINE) { tab = Tab.ENGINE }
            DockButton(Icons.Filled.Build, "Entretien", tab == Tab.MAINTENANCE) { tab = Tab.MAINTENANCE }
            DockButton(Icons.Filled.GraphicEq, "Son", tab == Tab.SOUND) { tab = Tab.SOUND }
            DockButton(Icons.Filled.PhoneIphone, "CarPlay", tint = C.Green) { launchSlot(ctx, Slot.CARPLAY) }
            DockButton(Icons.Filled.MusicNote, "Musique", tint = Color(0xFFFF4FA3)) { launchSlot(ctx, Slot.MUSIC) }
            DockButton(Icons.Filled.Radio, "Radio", tint = C.Orange) { launchSlot(ctx, Slot.RADIO) }
            DockButton(Icons.Filled.Apps, "Applis") { showApps = true }
            DockButton(Icons.Filled.Settings, "Réglages") { showSettings = true }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
                Tab.HOME, Tab.MAINTENANCE -> HomeAndMaintenanceScreen(vs, items, interior = tab == Tab.MAINTENANCE) { tab = it }
                Tab.VEHICLE -> VehicleScreen(vs)
                Tab.ENGINE -> EngineScreen()
                Tab.SOUND -> SoundScreen()
            }
        }
    }
    if (showApps) AppDrawer { showApps = false }
    if (showSettings) SettingsDialog { showSettings = false }
    if (!UiPrefs.welcomed) WelcomeDialog(onSettings = { showSettings = true })
    // v0.26 : bilan « Prêt à partir », une fois au lancement, 8 s après (le temps que le boîtier OBD réponde),
    // seulement à l'arrêt et s'il y a quelque chose à dire.
    var showReady by remember { mutableStateOf(false) }
    LaunchedEffect(UiPrefs.welcomed, UiPrefs.readyScreen) {
        if (UiPrefs.welcomed && UiPrefs.readyScreen && UiPrefs.readyDue()) {
            delay(8_000)
            val moving = fr.p308.lanceur.drive.Trip.state.value.currentKmh > 5f && !Vehicle.demo
            if (!moving && !showSettings && readyLinesNow().isNotEmpty()) {
                UiPrefs.markReadyShown()
                showReady = true
            }
        }
    }
    if (showReady) ReadyDialog(autoCloseS = 15) { showReady = false }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, selected: Boolean = false, tint: Color = C.Text, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.Accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) C.Accent else tint, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 10.sp, color = if (selected) C.Accent else C.Sub, maxLines = 1)
    }
}
