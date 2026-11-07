package fr.p308.lanceur.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import fr.p308.lanceur.vehicle.Opening

/** Vue de dessus de la 308, ouvrants animés et arcs du radar de recul. */
@Composable
fun CarTopView(
    open: Set<Opening>,
    color: Color,
    fiveDoors: Boolean,
    radarCm: List<Int?>,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val anim = Opening.entries.associateWith { o ->
        animateFloatAsState(if (o in open) 1f else 0f, tween(450), label = o.name).value
    }
    Canvas(modifier) {
        if (horizontal) {
            rotate(90f, pivot = center) { drawCar(Size(size.height, size.width), anim, color, fiveDoors, radarCm) }
        } else {
            drawCar(size, anim, color, fiveDoors, radarCm)
        }
    }
}

private val Glass = Color(0xFF16203A)
private val Tyre = Color(0xFF08090C)

private fun DrawScope.drawCar(box: Size, open: Map<Opening, Float>, body: Color, fiveDoors: Boolean, radar: List<Int?>) {
    // Longueur L, largeur W (proportions d'une 308 vue de dessus), plus la place du radar derrière.
    val l = minOf(box.height / 1.25f, box.width * 0.75f / 0.44f)
    val w = l * 0.44f
    val left = center.x - w / 2
    val right = center.x + w / 2
    val top = center.y - l * 1.22f / 2
    fun x(f: Float) = left + w * f
    fun y(f: Float) = top + l * f

    if (radar.isNotEmpty()) drawRadar(Offset(center.x, y(0.92f)), w * 0.42f, l * 0.055f, radar)

    val wheel = Size(w * 0.13f, l * 0.15f)
    for (fy in listOf(0.15f, 0.70f)) {
        drawRoundRect(Tyre, Offset(left - wheel.width * 0.35f, y(fy)), wheel, CornerRadius(wheel.width * 0.3f))
        drawRoundRect(Tyre, Offset(right - wheel.width * 0.65f, y(fy)), wheel, CornerRadius(wheel.width * 0.3f))
    }
    drawOval(body, Offset(left - w * 0.10f, y(0.335f)), Size(w * 0.13f, l * 0.035f))
    drawOval(body, Offset(right - w * 0.03f, y(0.335f)), Size(w * 0.13f, l * 0.035f))

    val radius = CornerRadius(w * 0.30f)
    drawRoundRect(body, Offset(left, top), Size(w, l), radius)
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color.Black.copy(0.30f), Color.Transparent, Color.Black.copy(0.30f)), left, right),
        Offset(left, top), Size(w, l), radius,
    )
    drawRoundRect(Color.White.copy(0.25f), Offset(left, top), Size(w, l), radius, style = Stroke(2f))

    open[Opening.HOOD]?.takeIf { it > 0f }?.let {
        drawRoundRect(C.Red.copy(alpha = 0.6f * it), Offset(left, top), Size(w, l * 0.27f), radius)
    }

    drawPath(Path().apply {
        moveTo(x(0.12f), y(0.28f)); lineTo(x(0.88f), y(0.28f))
        lineTo(x(0.83f), y(0.40f)); lineTo(x(0.17f), y(0.40f)); close()
    }, Glass)
    drawRoundRect(lerp(body, Color.Black, 0.18f), Offset(x(0.17f), y(0.40f)), Size(w * 0.66f, l * 0.38f), CornerRadius(w * 0.08f))
    drawRoundRect(Glass, Offset(x(0.07f), y(0.41f)), Size(w * 0.07f, l * 0.36f), CornerRadius(w * 0.03f))
    drawRoundRect(Glass, Offset(x(0.86f), y(0.41f)), Size(w * 0.07f, l * 0.36f), CornerRadius(w * 0.03f))
    drawPath(Path().apply {
        moveTo(x(0.17f), y(0.78f)); lineTo(x(0.83f), y(0.78f))
        lineTo(x(0.86f), y(0.86f)); lineTo(x(0.14f), y(0.86f)); close()
    }, Glass)

    open[Opening.TRUNK]?.takeIf { it > 0f }?.let {
        drawRoundRect(C.Red.copy(alpha = 0.6f * it), Offset(x(0.04f), y(0.86f)), Size(w * 0.92f, l * 0.14f), CornerRadius(w * 0.2f))
        drawRoundRect(C.Red.copy(alpha = 0.85f * it), Offset(x(0.08f), y(0.97f)), Size(w * 0.84f, l * 0.10f * it), CornerRadius(w * 0.08f))
    }

    // Portes : charnière à l'avant, ouverture vers l'extérieur.
    val segments = if (fiveDoors) {
        listOf(Triple(Opening.FRONT_LEFT, Opening.FRONT_RIGHT, 0.36f to 0.58f), Triple(Opening.REAR_LEFT, Opening.REAR_RIGHT, 0.58f to 0.77f))
    } else {
        listOf(Triple(Opening.FRONT_LEFT, Opening.FRONT_RIGHT, 0.36f to 0.68f))
    }
    val seam = Color.Black.copy(0.45f)
    for ((ol, or, seg) in segments) {
        val (a, b) = seg
        for (fy in listOf(a, b)) {
            drawLine(seam, Offset(left + 2f, y(fy)), Offset(x(0.12f), y(fy)), 2f)
            drawLine(seam, Offset(x(0.88f), y(fy)), Offset(right - 2f, y(fy)), 2f)
        }
        drawDoor(Offset(left + w * 0.02f, y(a)), l * (b - a), w * 0.07f, open[ol] ?: 0f, leftSide = true, body)
        drawDoor(Offset(right - w * 0.02f, y(a)), l * (b - a), w * 0.07f, open[or] ?: 0f, leftSide = false, body)
    }
}

private fun DrawScope.drawDoor(hinge: Offset, length: Float, thick: Float, t: Float, leftSide: Boolean, body: Color) {
    if (t <= 0.01f) return
    rotate(if (leftSide) 55f * t else -55f * t, pivot = hinge) {
        val x = if (leftSide) hinge.x - thick else hinge.x
        drawRoundRect(lerp(body, C.Red, 0.65f), Offset(x, hinge.y), Size(thick, length), CornerRadius(thick * 0.4f))
    }
}

/** 4 capteurs, de gauche à droite ; plus l'obstacle est proche, plus il y a d'arcs allumés. */
private fun DrawScope.drawRadar(c: Offset, r0: Float, step: Float, radar: List<Int?>) {
    radar.take(4).forEachIndexed { i, cm ->
        val level = when {
            cm == null -> 0
            cm <= 40 -> 3
            cm <= 90 -> 2
            cm <= 150 -> 1
            else -> 0
        }
        val lit = when (level) { 3 -> C.Red; 2 -> C.Orange; else -> C.Green }
        val start = 150f - (i + 1) * 30f + 2f
        for (ring in 0 until 3) {
            val r = r0 + (2 - ring) * step
            drawArc(
                if (ring < level) lit else Color.White.copy(0.08f),
                startAngle = start, sweepAngle = 26f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
                style = Stroke(width = step * 0.7f, cap = StrokeCap.Round),
            )
        }
    }
}
