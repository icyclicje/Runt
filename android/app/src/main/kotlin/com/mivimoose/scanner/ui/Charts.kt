package com.mivimoose.scanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class ChartPoint(val t: Long, val v: Double)

private val tickStyle = TextStyle(fontSize = 10.sp, color = Vault.axisText)

/** Round tick values covering [lo, hi]. */
fun niceTicks(lo: Double, hi: Double, n: Int = 4): List<Double> {
    if (hi <= lo) return listOf(lo)
    val raw = (hi - lo) / n
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = floor(lo / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= hi + step * 0.001 }.filter { it >= lo - step * 0.001 }.toList()
}

private val dayFmt = DateTimeFormatter.ofPattern("MMM d")
private val monthFmt = DateTimeFormatter.ofPattern("MMM yy")
fun fmtDay(t: Long, long: Boolean = false): String =
    Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().format(if (long) monthFmt else dayFmt)

/**
 * A single-series line chart: 2px line over a 10% area wash, hairline grid, round ticks,
 * an end dot, and a touch crosshair with a tooltip (value first, date second).
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    color: Color = Vault.series1,
    fmtValue: (Double) -> String,
    fmtTime: (Long) -> String = { fmtDay(it) },
    zeroBased: Boolean = false,
) {
    val tm = rememberTextMeasurer()
    var hover by remember(points) { mutableStateOf<Int?>(null) }
    var boxW by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val leftPad = 52.dp; val bottomPad = 20.dp; val topPad = 12.dp; val rightPad = 10.dp
    Box(modifier.fillMaxWidth().height(height).onSizeChanged { boxW = it.width }) {
        if (points.size < 2) {
            Text("Not enough sales data yet", color = Vault.muted, fontSize = 13.sp, modifier = Modifier.padding(start = leftPad, top = height / 2 - 10.dp))
            return@Box
        }
        val vMin0 = points.minOf { it.v }; val vMax0 = points.maxOf { it.v }
        val padV = max((vMax0 - vMin0) * 0.08, max(vMax0 * 0.02, 0.01))
        val lo = if (zeroBased) 0.0 else max(0.0, vMin0 - padV)
        val ticks = niceTicks(lo, vMax0 + padV)
        val yLo = min(lo, ticks.first()); val yHi = max(vMax0 + padV, ticks.last())
        val t0 = points.first().t; val t1 = points.last().t
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(points) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun pick(x: Float) {
                        val l = leftPad.toPx(); val r = size.width - rightPad.toPx()
                        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
                        val tt = t0 + (t1 - t0) * f
                        hover = points.indices.minBy { abs(points[it].t - tt) }
                    }
                    pick(down.position.x)
                    do {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: break
                        if (ch.positionChange() != Offset.Zero) { pick(ch.position.x); ch.consume() }
                    } while (e.changes.any { it.pressed })
                    hover = null
                }
            },
        ) {
            val l = leftPad.toPx(); val r = size.width - rightPad.toPx()
            val top = topPad.toPx(); val bottom = size.height - bottomPad.toPx()
            fun x(t: Long) = if (t1 == t0) l else l + (r - l) * ((t - t0).toFloat() / (t1 - t0))
            fun y(v: Double) = bottom - (bottom - top) * ((v - yLo) / (yHi - yLo)).toFloat()
            for (tv in ticks) {
                val yy = y(tv)
                drawLine(Vault.grid, Offset(l, yy), Offset(r, yy), 1f)
                val lay = tm.measure(fmtValue(tv), tickStyle)
                drawText(lay, topLeft = Offset(l - 6.dp.toPx() - lay.size.width, yy - lay.size.height / 2))
            }
            drawLine(Vault.axis, Offset(l, bottom), Offset(r, bottom), 1f)
            for ((i, tt) in listOf(t0, (t0 + t1) / 2, t1).withIndex()) {
                val lay = tm.measure(fmtTime(tt), tickStyle)
                val xx = when (i) { 0 -> l; 2 -> r - lay.size.width; else -> x(tt) - lay.size.width / 2 }
                drawText(lay, topLeft = Offset(xx, bottom + 4.dp.toPx()))
            }
            val line = Path(); val area = Path()
            points.forEachIndexed { i, p ->
                val px = x(p.t); val py = y(p.v)
                if (i == 0) { line.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py) } else { line.lineTo(px, py); area.lineTo(px, py) }
            }
            area.lineTo(x(t1), bottom); area.close()
            drawPath(area, color.copy(alpha = 0.10f))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val last = points.last()
            drawCircle(Vault.surface, 6.dp.toPx(), Offset(x(last.t), y(last.v)))
            drawCircle(color, 4.dp.toPx(), Offset(x(last.t), y(last.v)))
            hover?.let { h ->
                val p = points[h]
                drawLine(Vault.text2.copy(alpha = 0.6f), Offset(x(p.t), top), Offset(x(p.t), bottom), 1f)
                drawCircle(Vault.surface, 6.dp.toPx(), Offset(x(p.t), y(p.v)))
                drawCircle(color, 4.dp.toPx(), Offset(x(p.t), y(p.v)))
            }
        }
        hover?.let { h ->
            val p = points[h]
            val l = with(density) { leftPad.toPx() }; val r = boxW - with(density) { rightPad.toPx() }
            val xPx = if (t1 == t0) l else l + (r - l) * ((p.t - t0).toFloat() / (t1 - t0))
            val tipW = with(density) { 116.dp.toPx() }
            val left = (xPx - tipW / 2).coerceIn(0f, max(0f, boxW - tipW))
            Tooltip(fmtValue(p.v), fmtTime(p.t), Modifier.offset { IntOffset(left.roundToInt(), 0) })
        }
    }
}

@Composable
fun Tooltip(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Vault.surface3).border(1.dp, Vault.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(value, color = Vault.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, color = Vault.text2, fontSize = 11.sp)
    }
}

/**
 * Columns from one baseline (<= 24dp wide, 4dp rounded tops, 2px gaps), each column its
 * own touch target with a tooltip. Labels below; the highlighted column's value on its cap.
 */
@Composable
fun ColumnChart(
    labels: List<String>,
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    color: Color = Vault.series1,
    highlight: Int? = null,
    fmtValue: (Double) -> String,
    tooltipLabel: (Int) -> String = { labels[it] },
) {
    val tm = rememberTextMeasurer()
    var hover by remember(values) { mutableStateOf<Int?>(null) }
    var boxW by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val n = values.size
    if (n == 0) return
    val vMax = max(values.max(), 1e-9)
    Box(modifier.fillMaxWidth().height(height).onSizeChanged { boxW = it.width }) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(values) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun pick(x: Float) { hover = (x / (size.width / n)).toInt().coerceIn(0, n - 1) }
                    pick(down.position.x)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.firstOrNull()?.let { if (it.positionChange() != Offset.Zero) { pick(it.position.x); it.consume() } }
                    } while (e.changes.any { it.pressed })
                    hover = null
                }
            },
        ) {
            val bottom = size.height - 18.dp.toPx()
            val top = 16.dp.toPx()
            val slot = size.width / n
            val bw = min(24.dp.toPx(), slot - 2f)
            drawLine(Vault.axis, Offset(0f, bottom), Offset(size.width, bottom), 1f)
            for (i in 0 until n) {
                val cx = slot * i + slot / 2
                val h = ((values[i] / vMax) * (bottom - top)).toFloat()
                val c = if (hover == i) color.copy(alpha = 0.75f) else color
                if (h > 0.5f) {
                    val r = min(4.dp.toPx(), h)
                    val rr = RoundRect(cx - bw / 2, bottom - h, cx + bw / 2, bottom, CornerRadius(r, r), CornerRadius(r, r), CornerRadius.Zero, CornerRadius.Zero)
                    drawPath(Path().apply { addRoundRect(rr) }, c)
                }
                val lay = tm.measure(labels[i], tickStyle)
                drawText(lay, topLeft = Offset(cx - lay.size.width / 2, bottom + 3.dp.toPx()))
                if (i == highlight && values[i] > 0) {
                    val v = tm.measure(fmtValue(values[i]), TextStyle(fontSize = 10.sp, color = Vault.text2, fontWeight = FontWeight.SemiBold))
                    drawText(v, topLeft = Offset((cx - v.size.width / 2).coerceIn(0f, size.width - v.size.width), bottom - h - v.size.height - 2.dp.toPx()))
                }
            }
        }
        hover?.let { i ->
            val slot = boxW.toFloat() / n
            val tipW = with(density) { 110.dp.toPx() }
            val left = (slot * i + slot / 2 - tipW / 2).coerceIn(0f, max(0f, boxW - tipW))
            Tooltip(fmtValue(values[i]), tooltipLabel(i), Modifier.offset { IntOffset(left.roundToInt(), 0) })
        }
    }
}

/** A tiny trend line without axes (stat tiles, lists). */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = Vault.series1) {
    if (values.size < 2) return
    Canvas(modifier) {
        val lo = values.min(); val hi = values.max()
        val span = if (hi - lo < 1e-9) 1.0 else hi - lo
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - size.height * ((v - lo) / span).toFloat() * 0.9f - size.height * 0.05f
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Segmented bar (thin): one whole split into parts, e.g. owned vs missing value. */
@Composable
fun SegmentBar(parts: List<Pair<Double, Color>>, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val total = parts.sumOf { it.first }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (total <= 0) { drawRoundRect(Vault.surface3, cornerRadius = CornerRadius(size.height / 2)); return@Canvas }
        var x = 0f
        val gap = 2f
        for ((v, c) in parts) {
            if (v <= 0) continue
            val w = (size.width * (v / total)).toFloat()
            drawRoundRect(c, Offset(x, 0f), Size(max(0f, w - gap), size.height), CornerRadius(size.height / 2))
            x += w
        }
    }
}

/** Rounded integer for axis ticks in money. */
fun ceilNice(v: Double): Double = ceil(v)
