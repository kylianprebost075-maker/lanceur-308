package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/** Sélecteur d'onglets compact (pastilles). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(C.Field.copy(0.6f)).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (on) Color.White else C.Sub,
                modifier = Modifier.clip(RoundedCornerShape(9.dp))
                    .background(if (on) C.Accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun Tile(
    modifier: Modifier = Modifier,
    background: Brush = Brush.verticalGradient(listOf(C.Card2, C.Card)),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content,
    )
}
