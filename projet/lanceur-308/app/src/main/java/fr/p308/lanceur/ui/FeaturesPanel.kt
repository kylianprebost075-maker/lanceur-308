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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.vehicle.CarFeatures
import fr.p308.lanceur.vehicle.Engine
import fr.p308.lanceur.vehicle.Feature
import fr.p308.lanceur.vehicle.GpsMonitor

/**
 * Véhicule → « Mon autoradio » (v0.31) : ce que l'autoradio transmet vraiment au Lanceur, vu depuis son lancement.
 * Vert = reçu ; gris = pas encore vu, avec le geste qui permet de le tester. Utile à qui installe l'appli sur une
 * autre voiture : il sait tout de suite ce qui marche chez lui.
 */
@Composable
fun FeaturesPanel() {
    val ctx = LocalContext.current
    val seen by CarFeatures.seen.collectAsStateWithLifecycle()
    val e by Engine.state.collectAsStateWithLifecycle()
    val gps by GpsMonitor.state.collectAsStateWithLifecycle()
    val bass by fr.p308.lanceur.sound.BassAuto.status.collectAsStateWithLifecycle()
    val eqApp = remember { ctx.packageManager.getLaunchIntentForPackage(fr.p308.lanceur.sound.ChampService.EQ_PKG) != null }
    val (n, total) = CarFeatures.count(seen)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1.3f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Ce que mon autoradio transmet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("$n sur $total reçus depuis le lancement · contact mis, rien à régler", fontSize = 12.sp, color = C.Sub)
                Spacer(Modifier.height(6.dp))
                Feature.values().forEach { f ->
                    val v = seen[f]
                    FeatureRow(f.title, v ?: f.howToTest, v != null)
                }
            }
        }
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Le reste", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                FeatureRow("Boîtier OBD (moteur)", if (e.connected) "connecté" else e.status, e.connected)
                FeatureRow("GPS", gps.fixAgeS?.let { if (it < 10) "position reçue" else "dernière position il y a ${it.toInt()} s" }
                    ?: "pas encore de position (ciel dégagé, 1 à 5 min)", gps.fixAgeS != null)
                FeatureRow("Basses automatiques", when (bass) {
                    fr.p308.lanceur.sound.BassAuto.Status.ON -> "l'autoradio accepte l'égaliseur"
                    fr.p308.lanceur.sound.BassAuto.Status.UNSUPPORTED -> "refusées par cet autoradio"
                    else -> "coupées (Son → Options pour essayer)"
                }, bass == fr.p308.lanceur.sound.BassAuto.Status.ON)
                FeatureRow("Égaliseur FYT (balance Seul / Passagers)", if (eqApp) "présent" else "absent : balance à régler à la main", eqApp)
                FeatureRow("Feux et ceintures", "non transmis par le boîtier de l'auteur (Raise PSA, essais du 11/11)", false)
                Spacer(Modifier.weight(1f))
                Text(
                    "Une ligne grise n'est pas forcément absente : certaines informations n'arrivent qu'avec un geste. " +
                        "Si rien n'arrive du tout, l'autoradio n'est peut-être pas un FYT : le Lanceur marche quand même, " +
                        "sans ces informations.",
                    fontSize = 11.sp, color = C.Sub,
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(title: String, detail: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.Field.copy(0.45f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (ok) C.Green else Color.Gray.copy(0.5f)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, color = if (ok) C.Green else C.Sub, maxLines = 2)
        }
    }
}
