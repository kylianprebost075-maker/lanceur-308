package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import fr.p308.lanceur.UiPrefs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette : version jour, ou version nuit plus sombre et moins contrastée (lue en direct : bascule immédiate). */
object C {
    private val n get() = Night.active
    val Bg get() = if (n) Color(0xFF03050A) else Color(0xFF0A0F1E)
    val Card get() = if (n) Color(0xFF090D18) else Color(0xFF141B31)
    val Card2 get() = if (n) Color(0xFF0E1322) else Color(0xFF1C2542)
    val Field get() = if (n) Color(0xFF151B2E) else Color(0xFF243058)
    val Accent get() = if (n) Color(0xFF2C58BD) else Color(0xFF3D7BFF)
    val Green get() = if (n) Color(0xFF55B02A) else Color(0xFF7CFC3A)
    val Orange get() = if (n) Color(0xFFC07C1E) else Color(0xFFFFA726)
    val Red get() = if (n) Color(0xFFC23A3A) else Color(0xFFFF4D4D)
    val Text get() = if (n) Color(0xFFAEB6CC) else Color(0xFFEAF0FF)
    val Sub get() = if (n) Color(0xFF636D88) else Color(0xFF8C97B8)
}

@Composable
fun LanceurTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, onPrimary = Color.White,
            background = C.Bg, onBackground = C.Text,
            surface = C.Card, onSurface = C.Text,
            surfaceVariant = C.Card2, onSurfaceVariant = C.Sub,
            surfaceContainerHigh = C.Card2,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides C.Text, content = content)
    }
}

/**
 * Style « Verre » : surfaces translucides avec un liseré lumineux (reflet en haut à gauche) posées sur un fond
 * dégradé coloré. Volontairement SANS flou en temps réel : un flou d'arrière-plan recalculé à chaque image
 * coûterait cher sur l'autoradio ; ici tous les dégradés sont calculés une fois par taille (drawWithCache).
 */
object GlassStyle {
    val on: Boolean get() = UiPrefs.glass
    private val n get() = Night.active
    val fill: Brush
        get() = Brush.linearGradient(
            listOf(Color.White.copy(if (n) 0.07f else 0.13f), Color.White.copy(if (n) 0.025f else 0.05f)),
        )
    val edge: Brush
        get() = Brush.linearGradient(
            0f to Color.White.copy(if (n) 0.22f else 0.42f),
            0.35f to Color.White.copy(0.07f),
            0.75f to Color.White.copy(0.03f),
            1f to Color.White.copy(if (n) 0.08f else 0.16f),
        )
}

/** Surface verre (ou opaque classique) avec reflet doux en haut à gauche. */
fun Modifier.glassSurface(shape: Shape, classic: Brush? = null): Modifier {
    if (!GlassStyle.on) return clip(shape).background(classic ?: Brush.verticalGradient(listOf(C.Card2, C.Card)))
    val night = Night.active
    return clip(shape)
        .background(GlassStyle.fill)
        .drawWithCache {
            val glow = Brush.radialGradient(
                listOf(Color.White.copy(if (night) 0.04f else 0.09f), Color.Transparent),
                center = Offset(size.width * 0.18f, 0f), radius = size.maxDimension * 0.7f,
            )
            onDrawBehind { drawRect(glow) }
        }
        .border(1.dp, GlassStyle.edge, shape)
}

/** Fond de l'appli : dégradé sombre et taches de couleur douces (vues à travers le verre). */
fun Modifier.appBackground(): Modifier {
    if (!GlassStyle.on) return background(C.Bg)
    val night = Night.active
    val k = if (night) 0.45f else 1f
    return drawWithCache {
        val w = size.width
        val h = size.height
        val base = Brush.verticalGradient(listOf(Color(0xFF0C1733), Color(0xFF070A16), Color(0xFF05070F)))
        val blobs = listOf(
            Brush.radialGradient(listOf(Color(0xFF2F6BFF).copy(0.42f * k), Color.Transparent), Offset(w * 0.12f, h * 0.05f), w * 0.55f),
            Brush.radialGradient(listOf(Color(0xFF7A3CFF).copy(0.30f * k), Color.Transparent), Offset(w * 0.92f, h * 0.95f), w * 0.5f),
            Brush.radialGradient(listOf(Color(0xFF00C2B8).copy(0.18f * k), Color.Transparent), Offset(w * 0.62f, h * 0.18f), w * 0.35f),
        )
        onDrawBehind {
            drawRect(base)
            blobs.forEach { drawRect(it) }
            if (night) drawRect(Color.Black.copy(0.35f))
        }
    }
}

/** Sélecteur d'onglets compact (pastilles). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(if (GlassStyle.on) 16.dp else 12.dp)
    Row(
        (if (GlassStyle.on) modifier.glassSurface(shape) else modifier.clip(shape).background(C.Field.copy(0.6f))).padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (on) Color.White else C.Sub,
                modifier = Modifier.clip(RoundedCornerShape(if (GlassStyle.on) 13.dp else 9.dp))
                    .then(
                        if (!on) Modifier
                        else if (GlassStyle.on) Modifier.background(Brush.verticalGradient(listOf(C.Accent.copy(0.95f), C.Accent.copy(0.7f))))
                            .border(1.dp, Color.White.copy(0.3f), RoundedCornerShape(13.dp))
                        else Modifier.background(C.Accent),
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun Tile(
    modifier: Modifier = Modifier,
    background: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .glassSurface(RoundedCornerShape(if (GlassStyle.on) 24.dp else 20.dp), background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content,
    )
}
