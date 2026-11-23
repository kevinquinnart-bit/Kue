package art.kquinn.kuetv

// KueIcons.kt · version 1 (KueTV 0.1.204)

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * KueTV's own small pictures, drawn in the same plain line style as the section pictures on Home, in place of
 * the phone-style emoji that were in front of categories, the weather and elsewhere (Kevin, 23 November 2026).
 */
enum class Glyph { STAR, CLOCK, ALL, FOLDER, BALL, NEWS, GLOBE, NOTE, FILM, TV, KIDS, MORE, AERIAL, SPEAKER, SUN, PART_CLOUD, CLOUD, FOG, RAIN, SNOW, STORM }

/** The picture for each of Live TV's topics, by the topic's key in [Topics]. */
fun topicGlyph(key: String): Glyph = when (key) {
    "kids" -> Glyph.KIDS
    "sport" -> Glyph.BALL
    "news" -> Glyph.NEWS
    "docs" -> Glyph.GLOBE
    "music" -> Glyph.NOTE
    "films" -> Glyph.FILM
    "ent" -> Glyph.TV
    else -> Glyph.MORE
}

/** Open-Meteo's weather codes as one of the pictures. */
fun weatherGlyph(code: Int): Glyph = when (code) {
    0 -> Glyph.SUN
    1, 2 -> Glyph.PART_CLOUD
    45, 48 -> Glyph.FOG
    in 51..67, in 80..82 -> Glyph.RAIN
    in 71..77, 85, 86 -> Glyph.SNOW
    in 95..99 -> Glyph.STORM
    else -> Glyph.CLOUD
}

@Composable
fun KueIcon(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) { draw(glyph, color) }
}

private fun DrawScope.draw(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    val line = w * 0.08f
    val stroke = Stroke(width = line, cap = StrokeCap.Round)
    fun at(x: Float, y: Float) = Offset(w * x, h * y)
    fun bar(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = line) = drawLine(color, at(x1, y1), at(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
    // A filled cloud, its middle at [cx], [cy], [s] times its full size.
    fun cloud(cx: Float, cy: Float, s: Float) {
        drawCircle(color, radius = w * 0.17f * s, center = at(cx - 0.17f * s, cy + 0.02f * s))
        drawCircle(color, radius = w * 0.23f * s, center = at(cx + 0.02f * s, cy - 0.06f * s))
        drawCircle(color, radius = w * 0.15f * s, center = at(cx + 0.22f * s, cy + 0.04f * s))
        drawRoundRect(color, topLeft = at(cx - 0.34f * s, cy), size = Size(w * 0.7f * s, h * 0.2f * s), cornerRadius = CornerRadius(w * 0.1f * s))
    }
    when (glyph) {
        Glyph.STAR -> {
            val star = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.47f else 0.2f
                val a = Math.toRadians(-90.0 + 36.0 * i)
                val p = at(0.5f + r * cos(a).toFloat(), 0.54f + r * sin(a).toFloat())
                if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
            }
            star.close()
            drawPath(star, color)
        }
        Glyph.CLOCK -> {
            drawCircle(color, radius = w * 0.4f, center = center, style = stroke)
            bar(0.5f, 0.5f, 0.5f, 0.26f)
            bar(0.5f, 0.5f, 0.68f, 0.6f)
        }
        Glyph.ALL -> for (x in 0..2) for (y in 0..2) {
            drawRoundRect(color, topLeft = at(0.1f + 0.3f * x, 0.1f + 0.3f * y), size = Size(w * 0.2f, h * 0.2f), cornerRadius = CornerRadius(w * 0.05f))
        }
        Glyph.FOLDER -> {
            val tab = Path().apply {
                moveTo(w * 0.08f, h * 0.36f)
                lineTo(w * 0.08f, h * 0.2f)
                lineTo(w * 0.38f, h * 0.2f)
                lineTo(w * 0.48f, h * 0.32f)
            }
            drawPath(tab, color, style = stroke)
            drawRoundRect(color, topLeft = at(0.08f, 0.32f), size = Size(w * 0.84f, h * 0.52f), cornerRadius = CornerRadius(w * 0.07f), style = stroke)
        }
        Glyph.BALL -> {
            drawCircle(color, radius = w * 0.41f, center = center, style = stroke)
            val patch = Path()
            for (i in 0 until 5) {
                val a = Math.toRadians(-90.0 + 72.0 * i)
                val p = at(0.5f + 0.15f * cos(a).toFloat(), 0.5f + 0.15f * sin(a).toFloat())
                if (i == 0) patch.moveTo(p.x, p.y) else patch.lineTo(p.x, p.y)
                bar(0.5f + 0.15f * cos(a).toFloat(), 0.5f + 0.15f * sin(a).toFloat(), 0.5f + 0.38f * cos(a).toFloat(), 0.5f + 0.38f * sin(a).toFloat(), line * 0.8f)
            }
            patch.close()
            drawPath(patch, color)
        }
        Glyph.NEWS -> {
            drawRoundRect(color, topLeft = at(0.1f, 0.16f), size = Size(w * 0.8f, h * 0.68f), cornerRadius = CornerRadius(w * 0.07f), style = stroke)
            drawRect(color, topLeft = at(0.22f, 0.3f), size = Size(w * 0.24f, h * 0.2f))
            bar(0.56f, 0.32f, 0.78f, 0.32f)
            bar(0.56f, 0.46f, 0.78f, 0.46f)
            bar(0.22f, 0.62f, 0.78f, 0.62f)
            bar(0.22f, 0.72f, 0.62f, 0.72f)
        }
        Glyph.GLOBE -> {
            drawCircle(color, radius = w * 0.41f, center = center, style = stroke)
            drawOval(color, topLeft = at(0.31f, 0.09f), size = Size(w * 0.38f, h * 0.82f), style = stroke)
            bar(0.1f, 0.5f, 0.9f, 0.5f)
            bar(0.2f, 0.3f, 0.8f, 0.3f, line * 0.8f)
            bar(0.2f, 0.7f, 0.8f, 0.7f, line * 0.8f)
        }
        Glyph.NOTE -> {
            drawOval(color, topLeft = at(0.12f, 0.66f), size = Size(w * 0.28f, h * 0.2f))
            drawOval(color, topLeft = at(0.56f, 0.58f), size = Size(w * 0.28f, h * 0.2f))
            bar(0.38f, 0.74f, 0.38f, 0.22f)
            bar(0.82f, 0.66f, 0.82f, 0.14f)
            bar(0.38f, 0.22f, 0.82f, 0.14f, line * 1.6f)
        }
        Glyph.FILM -> {
            drawRoundRect(color, topLeft = at(0.1f, 0.42f), size = Size(w * 0.8f, h * 0.44f), cornerRadius = CornerRadius(w * 0.06f), style = stroke)
            rotate(-14f, pivot = at(0.12f, 0.38f)) {
                drawRoundRect(color, topLeft = at(0.1f, 0.22f), size = Size(w * 0.8f, h * 0.14f), cornerRadius = CornerRadius(w * 0.04f))
            }
            bar(0.1f, 0.56f, 0.9f, 0.56f, line * 0.7f)
        }
        Glyph.TV -> {
            drawRoundRect(color, topLeft = at(0.08f, 0.16f), size = Size(w * 0.84f, h * 0.54f), cornerRadius = CornerRadius(w * 0.08f), style = stroke)
            bar(0.5f, 0.72f, 0.5f, 0.86f)
            bar(0.32f, 0.88f, 0.68f, 0.88f)
        }
        Glyph.KIDS -> {
            drawOval(color, topLeft = at(0.26f, 0.06f), size = Size(w * 0.48f, h * 0.56f), style = stroke)
            val knot = Path().apply {
                moveTo(w * 0.5f, h * 0.62f)
                lineTo(w * 0.44f, h * 0.7f)
                lineTo(w * 0.56f, h * 0.7f)
                close()
            }
            drawPath(knot, color)
            val string = Path().apply {
                moveTo(w * 0.5f, h * 0.7f)
                quadraticTo(w * 0.36f, h * 0.8f, w * 0.52f, h * 0.94f)
            }
            drawPath(string, color, style = Stroke(width = line * 0.7f, cap = StrokeCap.Round))
            // A shine on the balloon.
            drawArc(color, startAngle = 200f, sweepAngle = 50f, useCenter = false, topLeft = at(0.34f, 0.16f), size = Size(w * 0.3f, h * 0.34f), style = Stroke(width = line * 0.7f, cap = StrokeCap.Round))
        }
        Glyph.MORE -> {
            drawRoundRect(color, topLeft = at(0.08f, 0.22f), size = Size(w * 0.84f, h * 0.56f), cornerRadius = CornerRadius(w * 0.14f), style = stroke)
            for (i in 0..2) drawCircle(color, radius = w * 0.07f, center = at(0.3f + 0.2f * i, 0.5f))
        }
        Glyph.AERIAL -> {
            drawCircle(color, radius = w * 0.07f, center = at(0.5f, 0.34f))
            bar(0.5f, 0.42f, 0.5f, 0.9f)
            bar(0.34f, 0.9f, 0.66f, 0.9f)
            for (r in listOf(0.2f, 0.36f)) {
                val box = Size(w * r * 2, h * r * 2)
                val corner = at(0.5f - r, 0.34f - r)
                drawArc(color, startAngle = -40f, sweepAngle = 80f, useCenter = false, topLeft = corner, size = box, style = stroke)
                drawArc(color, startAngle = 140f, sweepAngle = 80f, useCenter = false, topLeft = corner, size = box, style = stroke)
            }
        }
        Glyph.SPEAKER -> {
            val body = Path().apply {
                moveTo(w * 0.1f, h * 0.38f)
                lineTo(w * 0.28f, h * 0.38f)
                lineTo(w * 0.5f, h * 0.18f)
                lineTo(w * 0.5f, h * 0.82f)
                lineTo(w * 0.28f, h * 0.62f)
                lineTo(w * 0.1f, h * 0.62f)
                close()
            }
            drawPath(body, color)
            for (r in listOf(0.16f, 0.32f)) {
                drawArc(color, startAngle = -45f, sweepAngle = 90f, useCenter = false, topLeft = at(0.5f - r, 0.5f - r), size = Size(w * r * 2, h * r * 2), style = stroke)
            }
        }
        Glyph.SUN -> {
            drawCircle(color, radius = w * 0.2f, center = center)
            for (i in 0 until 8) rotate(45f * i) { bar(0.5f, 0.06f, 0.5f, 0.18f) }
        }
        Glyph.PART_CLOUD -> {
            drawCircle(color, radius = w * 0.14f, center = at(0.36f, 0.34f))
            for (i in 0 until 8) rotate(45f * i, pivot = at(0.36f, 0.34f)) { bar(0.36f, 0.08f, 0.36f, 0.14f, line * 0.8f) }
            cloud(0.56f, 0.6f, 0.8f)
        }
        Glyph.CLOUD -> cloud(0.5f, 0.52f, 1f)
        Glyph.FOG -> {
            bar(0.12f, 0.3f, 0.88f, 0.3f)
            bar(0.2f, 0.5f, 0.8f, 0.5f)
            bar(0.12f, 0.7f, 0.88f, 0.7f)
        }
        Glyph.RAIN -> {
            cloud(0.5f, 0.36f, 0.9f)
            for (x in listOf(0.3f, 0.5f, 0.7f)) bar(x, 0.7f, x - 0.08f, 0.9f)
        }
        Glyph.SNOW -> {
            cloud(0.5f, 0.36f, 0.9f)
            for ((x, y) in listOf(0.3f to 0.78f, 0.5f to 0.88f, 0.7f to 0.78f)) drawCircle(color, radius = w * 0.06f, center = at(x, y))
        }
        Glyph.STORM -> {
            cloud(0.5f, 0.34f, 0.9f)
            val bolt = Path().apply {
                moveTo(w * 0.54f, h * 0.6f)
                lineTo(w * 0.38f, h * 0.8f)
                lineTo(w * 0.5f, h * 0.8f)
                lineTo(w * 0.42f, h * 0.98f)
                lineTo(w * 0.64f, h * 0.74f)
                lineTo(w * 0.52f, h * 0.74f)
                lineTo(w * 0.6f, h * 0.6f)
                close()
            }
            drawPath(bolt, color)
        }
    }
}
