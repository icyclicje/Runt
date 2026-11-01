package com.mivimoose.scanner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mivimoose.core.catalog.BundleParser
import com.mivimoose.core.catalog.Card
import com.mivimoose.scanner.Graph

val Rounded = RoundedCornerShape(20.dp)
val RoundedSmall = RoundedCornerShape(12.dp)
/** Standard trading card corner radius relative to width (~3.5 mm of 63 mm). */
fun cardShape(width: Dp) = RoundedCornerShape(width * 0.055f)

/** A glass panel. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    shape: Shape = Rounded,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Vault.surface2, Vault.surface)))
            .border(1.dp, Vault.line, shape)
            .then(if (onClick != null) Modifier.clickable { Graph.sounds.play("click"); onClick() } else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A holographic border; `phase` (0..1) slides the gradient round for animation. */
fun Modifier.holoBorder(width: Dp = 2.dp, shape: Shape = Rounded, phase: Float = 0f): Modifier = drawWithContent {
    drawContent()
    val outline = shape.createOutline(size, layoutDirection, this)
    val d = size.width + size.height
    val start = Offset(-d + 2 * d * phase, 0f)
    val brush = Brush.linearGradient(Vault.holo + Vault.holo + Vault.holo.first(), start, Offset(start.x + d, size.height), androidx.compose.ui.graphics.TileMode.Mirror)
    drawOutline(outline, brush, style = Stroke(width.toPx()))
}

/** The holo border, animated. */
@Composable
fun Modifier.holoBorderAnimated(width: Dp = 2.dp, shape: Shape = Rounded, periodMs: Int = 3000): Modifier {
    val t = rememberInfiniteTransition(label = "border")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    return holoBorder(width, shape, p)
}

/** Slowly rotating holo sheen over a composable (for hits). */
@Composable
fun HoloSheen(modifier: Modifier = Modifier, alpha: Float = 0.35f, shape: Shape = Rounded) {
    val t = rememberInfiniteTransition(label = "sheen")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "x")
    Canvas(modifier.clip(shape)) {
        val w = size.width; val h = size.height
        val start = Offset(w * x - w, 0f); val end = Offset(w * x, h)
        drawRect(Brush.linearGradient(
            listOf(Color.Transparent, Vault.mint.copy(alpha = alpha * 0.6f), Vault.orchid.copy(alpha = alpha), Vault.peach.copy(alpha = alpha * 0.6f), Color.Transparent),
            start, end,
        ))
    }
}

/** Glow behind something by value tier. */
fun Modifier.tierGlow(tier: Int, shape: Shape, small: Boolean = false): Modifier =
    if (tier <= 0) this else shadow(
        if (small) when (tier) { 3 -> 10.dp; 2 -> 7.dp; else -> 5.dp } else when (tier) { 3 -> 36.dp; 2 -> 26.dp; else -> 16.dp },
        shape, false, Vault.tierColor(tier), Vault.tierColor(tier),
    )

/** A card's picture: its scan, falling back to TCGplayer's image, then a placeholder. */
@Composable
fun CardImage(card: Card?, modifier: Modifier = Modifier, large: Boolean = false, dim: Boolean = false) {
    val urls = remember(card?.key, large) {
        if (card == null) emptyList() else listOfNotNull(
            (if (large) card.imgLarge else card.img).takeIf { it.isNotEmpty() },
            card.tcgId?.let { BundleParser.tcgImage(it, large) },
            if (large) card.img.takeIf { it.isNotEmpty() } else null,
        ).distinct()
    }
    var i by remember(urls) { mutableStateOf(0) }
    Box(modifier.aspectRatio(63f / 88f).clip(RoundedCornerShape(6)).background(Vault.surface3), contentAlignment = Alignment.Center) {
        if (i < urls.size) {
            AsyncImage(
                model = urls[i], contentDescription = card?.name, contentScale = ContentScale.Fit,
                onError = { i++ }, modifier = Modifier.fillMaxSize(),
                alpha = if (dim) 0.28f else 1f,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(Icons.Default.ImageNotSupported, null, tint = Vault.muted)
                Text(card?.name ?: "", style = MaterialTheme.typography.labelSmall, color = Vault.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A price in the user's currency. */
@Composable
fun Price(usd: Double?, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleMedium, color: Color = Vault.text) {
    Graph.money.rates.collectAsStateValue()
    Text(Graph.money.fmt(usd), modifier, style = style, color = color, maxLines = 1)
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T = collectAsState().value

/** Signed change with an arrow (never colour alone). */
@Composable
fun Delta(pct: Double?, modifier: Modifier = Modifier, small: Boolean = false) {
    if (pct == null || pct.isNaN() || pct.isInfinite()) return
    val up = pct >= 0
    val col = if (up) Vault.goodText else Vault.badText
    Row(
        modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.12f)).padding(horizontal = if (small) 6.dp else 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, null, tint = col, modifier = Modifier.size(if (small) 12.dp else 14.dp))
        Spacer(Modifier.width(3.dp))
        Text(Graph.money.pct(pct), color = col, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Progress ring with a holo arc. */
@Composable
fun Ring(progress: Float, size: Dp, stroke: Dp = 6.dp, modifier: Modifier = Modifier, track: Color = Vault.surface3, center: @Composable () -> Unit = {}) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(progress) { anim.animateTo(progress.coerceIn(0f, 1f), tween(900)) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val r = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(track, 0f, 360f, false, tl, r, style = Stroke(s))
            if (anim.value > 0f) {
                drawArc(Brush.sweepGradient(Vault.holo + Vault.holo.first()), -90f, 360f * anim.value, false, tl, r, style = Stroke(s, cap = StrokeCap.Round))
            }
        }
        center()
    }
}

/** A row of selectable pills. */
@Composable
fun <T> Pills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, label) in options) {
            val on = v == selected
            Text(
                label,
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Vault.text else Vault.surface2)
                    .border(1.dp, if (on) Color.Transparent else Vault.line, RoundedCornerShape(50))
                    .clickable { if (!on) { Graph.sounds.play("click"); onSelect(v) } }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                color = if (on) Vault.bg else Vault.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, color = Vault.mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onAction() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Vault.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Vault.mint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Vault.text2, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(14.dp)); action() }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Glass(modifier, padding = PaddingValues(14.dp)) {
        Text(label, style = Eyebrow)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        if (sub != null) Text(sub, color = Vault.muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** Text filled with the holo gradient, sweeping. */
@Composable
fun HoloText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "holoText")
    val off by t.animateFloat(0f, 600f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), label = "o")
    Text(text, modifier, style = style.copy(brush = Brush.linearGradient(Vault.holo, Offset(off - 300f, 0f), Offset(off + 300f, 80f))))
}

/** A small rarity / tag label. */
@Composable
fun Tag(text: String, color: Color = Vault.text2, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** Value tier of a price under the user's thresholds. */
fun tierOf(usd: Double): Int {
    val s = Graph.prefs.value
    return when { usd >= s.jackpotValue -> 3; usd >= s.bigValue -> 2; usd >= s.hitValue -> 1; else -> 0 }
}

/** Card search off the main thread, debounced while typing. */
@Composable
fun rememberSearch(q: String, lang: String? = null, setId: String? = null, limit: Int = 60): List<Card> {
    val cat = Graph.catalog.catalog.collectAsState().value
    val res = androidx.compose.runtime.produceState(emptyList<Card>(), q, lang, setId, cat) {
        if (cat == null || q.trim().length < 2) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(160)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { cat.search(q, setId, lang, limit) }
    }
    return res.value
}
