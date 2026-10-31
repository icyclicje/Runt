package com.mivimoose.scanner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mivimoose.scanner.Graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val vr: Float, val color: Color, val w: Float, val h: Float, val spark: Boolean)

/**
 * A burst of confetti (jackpot) or sparkles (big hit) from a point, falling with gravity.
 * `seed` restarts it.
 */
@Composable
fun Burst(seed: Long, modifier: Modifier = Modifier, count: Int = 140, sparkles: Boolean = false, origin: Offset = Offset(0.5f, 0.42f)) {
    if (Graph.prefs.value.reduceMotion) return
    val parts = remember(seed) {
        val r = Random(seed)
        val colors = Vault.holo + Vault.gold + Color.White
        List(count) {
            val a = r.nextFloat() * 6.283f
            val sp = (if (sparkles) 0.25f else 0.55f) + r.nextFloat() * (if (sparkles) 0.6f else 1.1f)
            Particle(origin.x, origin.y, cos(a) * sp, sin(a) * sp - (if (sparkles) 0.2f else 0.75f), r.nextFloat() * 360f,
                (r.nextFloat() - 0.5f) * 720f, colors[r.nextInt(colors.size)], 6f + r.nextFloat() * 8f, 3f + r.nextFloat() * 5f, sparkles || r.nextFloat() < 0.25f)
        }
    }
    var tick by remember(seed) { mutableLongStateOf(0L) }
    LaunchedEffect(seed) {
        var last = 0L
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            for (p in parts) { p.vy += 1.4f * dt; p.vx *= 0.995f; p.x += p.vx * dt * 0.6f; p.y += p.vy * dt * 0.6f; p.rot += p.vr * dt }
            tick = now
            if (now - start > 3_200_000_000L) break
        }
    }
    Canvas(modifier.fillMaxSize()) {
        tick.let { }
        for (p in parts) {
            val c = Offset(p.x * size.width, p.y * size.height)
            if (c.y > size.height + 20) continue
            if (p.spark) {
                drawCircle(p.color.copy(alpha = 0.9f), p.h * 0.7f, c)
                drawCircle(p.color.copy(alpha = 0.25f), p.h * 2.2f, c)
            } else rotate(p.rot, c) { drawRect(p.color, Offset(c.x - p.w / 2, c.y - p.h / 2), Size(p.w, p.h)) }
        }
    }
}

/** Full-screen celebration for a card above the jackpot value: flash, burst, JACKPOT, price. */
@Composable
fun JackpotOverlay(seed: Long, price: String, name: String, onDone: () -> Unit) {
    val flash = remember(seed) { Animatable(0.85f) }
    val scale = remember(seed) { Animatable(0.3f) }
    val fade = remember(seed) { Animatable(1f) }
    LaunchedEffect(seed) {
        launch { flash.animateTo(0f, tween(450)) }
        launch { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
        delay(2600)
        fade.animateTo(0f, tween(450))
        onDone()
    }
    Box(Modifier.fillMaxSize().alpha(fade.value), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Vault.gold.copy(alpha = 0.35f), Vault.orchid.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.55f)))))
        Burst(seed, count = 180)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale.value)) {
            HoloText("JACKPOT", TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp))
            androidx.compose.material3.Text(price, style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Vault.gold))
            androidx.compose.material3.Text(name, style = TextStyle(fontSize = 18.sp, color = Vault.text))
        }
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
    }
}

/** Shake offset (px) after a big pull. */
@Composable
fun rememberShake(seed: Long, strength: Float): Float {
    val a = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed) {
        if (seed == 0L || strength <= 0f || Graph.prefs.value.reduceMotion) return@LaunchedEffect
        repeat(7) { i -> a.animateTo(if (i % 2 == 0) strength else -strength, tween(45, easing = FastOutSlowInEasing)) }
        a.animateTo(0f, tween(60))
    }
    return a.value
}
