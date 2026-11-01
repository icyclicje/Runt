package com.mivimoose.scanner.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.LANGUAGE_FLAGS
import com.mivimoose.core.net.TcgPlayer
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes
import com.mivimoose.scanner.data.GradedInfo
import com.mivimoose.scanner.data.PricePoint
import com.mivimoose.scanner.data.Watch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun CardScreen(vm: AppVM, nav: NavHostController, key: String) {
    val cat by vm.catalog.collectAsState()
    val p by vm.portfolio.collectAsState()
    val watches by vm.watches.collectAsState()
    Graph.catalog.priceTick.collectAsState().value
    val card = cat?.card(key) ?: return
    val set = cat?.set(card.setId)
    val ctx = LocalContext.current
    var variant by rememberSaveable(key) { mutableStateOf(card.variants().first().id) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(key) { refreshing = true; Graph.prices.refreshCard(card); refreshing = false; Graph.catalog.refreshSet(card.setId, 720) }
    val price = card.priceFor(variant)
    val tier = tierOf(price)
    val ownedHere = p?.holdings?.filter { it.card.key == key } ?: emptyList()
    val watch = watches.firstOrNull { it.cardKey == key }
    var showWatch by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = BottomBarSpace + 16.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showWatch = true }) {
                Icon(if (watch != null) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, "Price alert", tint = if (watch != null) Vault.mint else Vault.text2)
            }
            IconButton(onClick = { nav.navigate(Routes.share("card", key)) }) { Icon(Icons.Default.IosShare, "Share", tint = Vault.text2) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            TiltCard(card, tier, Modifier.width(250.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, false))
                if (tier > 0) { Spacer(Modifier.width(8.dp)); Tag(Vault.tierName(tier), Vault.tierColor(tier)) }
            }
            card.nameLocal?.let { Text(it, color = Vault.text2) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${card.setName} · #${card.number}", color = Vault.mint, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickableSound { nav.navigate(Routes.set(card.setId)) })
                Spacer(Modifier.width(8.dp))
                if (card.rarity.isNotEmpty()) Tag(card.rarity, Vault.text2)
                if (card.lang != "en") { Spacer(Modifier.width(6.dp)); Tag(LANGUAGE_FLAGS[card.lang] ?: card.lang, Vault.periwinkle) }
            }
            Spacer(Modifier.height(16.dp))
            if (card.variants().size > 1) {
                Pills(card.variants().map { it.id to it.label }, variant, { variant = it })
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Graph.money.fmt(price), style = MaterialTheme.typography.displaySmall, color = if (tier > 0) Vault.tierColor(tier) else Vault.text)
                Spacer(Modifier.width(10.dp))
                if (refreshing) CircularProgressIndicator(Modifier.size(16.dp).padding(bottom = 10.dp), strokeWidth = 2.dp, color = Vault.muted)
            }
            PriceBasis(card, variant)
            if (card.lang == "ja" && card.tcgId == null) Text("Japanese print: estimated from the English card", color = Vault.muted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            MarketGraph(card, variant)
            Spacer(Modifier.height(14.dp))
            OwnedPanel(card, variant, ownedHere.sumOf { it.owned.qty }, ownedHere.joinToString(" · ") { "${it.owned.qty}× ${card.variantLabel(it.owned.variant)}${it.owned.grade?.let { g -> " $g" } ?: ""}" })
            if (Graph.prefs.value.showGraded) { Spacer(Modifier.height(14.dp)); GradedPanel(card, variant) }
            set?.let { s ->
                val odds = runCatching { Graph.catalog.packs.cardOdds(s, card) }.getOrDefault(0.0)
                if (odds > 0) {
                    Spacer(Modifier.height(14.dp))
                    Glass(Modifier.fillMaxWidth()) {
                        Text("PULL ODDS", style = Eyebrow)
                        Text("1 in ${"%,d".format((1 / odds).toLong())} packs", style = MaterialTheme.typography.titleLarge)
                        val src = Graph.catalog.packs.rateSource(s)
                        Text(
                            when (src) {
                                "documented" -> "From measured openings" + (Graph.catalog.packs.rateInfo(s)?.packsSampled?.let { " (${"%,d".format(it)} packs)" } ?: "")
                                "box" -> "Estimated from box guarantees"
                                else -> "Estimated for this era"
                            }, color = Vault.muted, fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.tcgId?.let { id -> LinkButton("TCGplayer") { open(ctx, TcgPlayer.productUrl(card.variants().firstOrNull { it.id == variant }?.productId ?: id)) } }
                LinkButton("eBay sold") { open(ctx, "https://www.ebay.com/sch/i.html?_nkw=" + Uri.encode("${card.name} ${card.number} ${card.setName}") + "&LH_Sold=1&LH_Complete=1") }
                Graph.prices.cachedGraded(card, variant)?.let { g -> LinkButton("PriceCharting") { open(ctx, g.url) } }
            }
        }
    }
    if (showWatch) WatchDialog(card, variant, price, watch, onDismiss = { showWatch = false })
}

fun open(ctx: Context, url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, fontSize = 13.sp); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp))
    }
}

fun Modifier.clickableSound(onClick: () -> Unit): Modifier = clickable { Graph.sounds.play("click"); onClick() }

/** A card that tilts with the phone and catches the light (hits get the holo sheen). */
@Composable
fun TiltCard(card: Card, tier: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var rx by remember { mutableFloatStateOf(0f) }
    var ry by remember { mutableFloatStateOf(0f) }
    val still = Graph.prefs.value.reduceMotion
    DisposableEffect(still) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { ry = (e.values[0] * 2.4f).coerceIn(-14f, 14f); rx = ((e.values[1] - 6f) * -1.8f).coerceIn(-12f, 12f) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (!still && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    val ax by animateFloatAsState(rx, label = "rx")
    val ay by animateFloatAsState(ry, label = "ry")
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.graphicsLayer { rotationX = ax; rotationY = ay; cameraDistance = 14f * density }.tierGlow(tier, shape)) {
        CardImage(card, Modifier.fillMaxWidth().clip(shape), large = true)
        if (tier >= 1 || card.isHit) HoloSheen(Modifier.matchParentSize(), alpha = if (tier >= 2) 0.4f else 0.22f, shape = shape)
        if (tier >= 2) Box(Modifier.matchParentSize().holoBorderAnimated(2.dp, shape))
    }
}

@Composable
private fun MarketGraph(card: Card, variant: String) {
    var range by rememberSaveable { mutableStateOf("quarter") }
    var pts by remember(card.key, variant, range) { mutableStateOf<List<PricePoint>?>(null) }
    var loading by remember(card.key, variant, range) { mutableStateOf(true) }
    LaunchedEffect(card.key, variant, range) {
        loading = true
        pts = Graph.prices.history(card, variant, range)
        loading = false
    }
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Market", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pills(listOf("month" to "1M", "quarter" to "3M", "semi-annual" to "6M", "annual" to "1Y"), range, { range = it })
        }
        val list = pts
        val chart = list?.map { ChartPoint(LocalDate.parse(it.day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), it.price) } ?: emptyList()
        if (chart.size >= 2) {
            val first = chart.first().v; val last = chart.last().v
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                if (first > 0) Delta((last - first) / first)
                Text("  over ${when (range) { "month" -> "1 month"; "quarter" -> "3 months"; "semi-annual" -> "6 months"; else -> "1 year" }} · TCGplayer sales, near mint", color = Vault.muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading && list == null -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.muted, strokeWidth = 2.dp) }
            list == null -> Text("No market history for this card (TCGplayer doesn't list it).", color = Vault.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 20.dp))
            else -> {
                LineChart(chart, height = 190.dp, fmtValue = { Graph.money.short(it) })
                if (list.any { it.sold > 0 }) {
                    Spacer(Modifier.height(12.dp))
                    Text("Copies sold per period", color = Vault.text2, fontSize = 12.sp)
                    ColumnChart(list.map { "" }, list.map { it.sold.toDouble() }, height = 70.dp, color = Vault.series1.copy(alpha = 0.7f),
                        fmtValue = { "%,d sold".format(it.toInt()) }, tooltipLabel = { "from " + fmtDay(chart[it].t) })
                }
            }
        }
    }
}

@Composable
private fun OwnedPanel(card: Card, variant: String, count: Int, detail: String) {
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("IN YOUR COLLECTION", style = Eyebrow)
                Text(if (count == 0) "Not owned yet" else "$count ${if (count == 1) "copy" else "copies"}", style = MaterialTheme.typography.titleMedium)
                if (detail.isNotEmpty()) Text(detail, color = Vault.muted, fontSize = 12.sp)
            }
            IconButton(onClick = { Graph.store.removeOne(card.key, null); Graph.sounds.play("undo") }, enabled = count > 0) { Icon(Icons.Default.Remove, "Remove one") }
            Button(onClick = { Graph.store.add(card.key, variant, Graph.prefs.value.defaultCondition); Graph.sounds.play("new"); Graph.sounds.buzz() },
                colors = ButtonDefaults.buttonColors(containerColor = Vault.mint, contentColor = Vault.bg)) {
                Icon(Icons.Default.Add, null); Text("Add")
            }
        }
    }
}

@Composable
private fun GradedPanel(card: Card, variant: String) {
    val set = Graph.catalog.catalog.value?.set(card.setId)
    var g by remember(card.key, variant) { mutableStateOf(Graph.prices.cachedGraded(card, variant)) }
    var loading by remember(card.key, variant) { mutableStateOf(g == null) }
    LaunchedEffect(card.key, variant) { if (g == null) { g = Graph.prices.graded(card, variant, set); loading = false } }
    Glass(Modifier.fillMaxWidth()) {
        Text("PSA POPULATION", style = Eyebrow)
        val gi = g
        when {
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.muted, strokeWidth = 2.dp) }
            gi == null -> Text("No graded data found for this card.", color = Vault.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            else -> GradedBody(gi)
        }
    }
}

@Composable
fun GradedBody(g: GradedInfo, compact: Boolean = false) {
    val psa = g.psa
    if (psa != null && psa.sum() > 0) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%,d".format(psa.sum()), style = MaterialTheme.typography.titleLarge)
            Text("  graded", color = Vault.text2, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            g.gemRate?.let { Column(horizontalAlignment = Alignment.End) { Text("%.1f%%".format(it * 100), style = MaterialTheme.typography.titleLarge); Text("gem rate (PSA 10)", color = Vault.muted, fontSize = 11.sp) } }
        }
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            ColumnChart((1..10).map { it.toString() }, psa.map { it.toDouble() }, height = 130.dp, highlight = 9,
                fmtValue = { "%,d".format(it.toInt()) }, tooltipLabel = { "PSA ${it + 1}" })
            Text("Grade", color = Vault.muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    } else if (g.universal == null) Text("No PSA population recorded yet.", color = Vault.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
    g.universal?.let { AllGraders(it, compact) }
    if (g.prices.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("GRADED PRICES", style = Eyebrow)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in listOf("Ungraded", "Grade 9", "PSA 10")) {
                val v = g.prices[k] ?: continue
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Vault.surface3).padding(10.dp)) {
                    Text(if (k == "Grade 9") "PSA 9" else if (k == "Ungraded") "Raw" else k, color = Vault.text2, fontSize = 12.sp)
                    Text(Graph.money.fmt(v, cents = v < 100), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!compact) GradedHistory(g)
    }
    if (g.psa != null || g.prices.isNotEmpty()) Text((if (g.exact) "PriceCharting" else "PriceCharting (closest match)") + " · population updated monthly", color = Vault.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
}

/** Population across graders (GemRate): copies and gem rate per grader, bars to scale. */
@Composable
private fun AllGraders(u: com.mivimoose.core.net.GemRate.Pop, compact: Boolean) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("ALL GRADERS", style = Eyebrow, modifier = Modifier.weight(1f))
        Text("%,d graded".format(u.total) + (u.gemRate?.let { " · %.1f%% gem".format(it * 100) } ?: ""), color = Vault.text2, fontSize = 12.sp)
    }
    Spacer(Modifier.height(6.dp))
    val names = mapOf("psa" to "PSA", "cgc" to "CGC", "beckett" to "Beckett", "sgc" to "SGC")
    val max = u.graders.values.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    for ((k, gp) in u.graders.entries.sortedByDescending { it.value.total }) {
        if (gp.total == 0) continue
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(names[k] ?: k.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Vault.surface3)) {
                Box(Modifier.fillMaxWidth(gp.total.toFloat() / max).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Vault.series1))
            }
            Text("%,d".format(gp.total), fontSize = 12.sp, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text(gp.gemRate?.let { "%.0f%%".format(it * 100) } ?: "–", color = Vault.text2, fontSize = 12.sp, modifier = Modifier.width(44.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        if (!compact) {
            val top = gp.grades.entries.sortedByDescending { it.key.substringBefore(' ').toDoubleOrNull() ?: 0.0 }.take(4)
            if (top.isNotEmpty()) Text(top.joinToString("  ·  ") { "${it.key}: %,d".format(it.value) }, color = Vault.muted, fontSize = 11.sp,
                modifier = Modifier.padding(start = 64.dp, bottom = 2.dp))
        }
    }
    Text("GemRate · gem = PSA 10, BGS 9.5+, SGC 10, CGC 10", color = Vault.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun GradedHistory(g: GradedInfo) {
    val keys = listOf("Ungraded", "Grade 9", "PSA 10").filter { (g.history[it]?.size ?: 0) >= 2 }
    if (keys.isEmpty()) return
    var k by remember(g.url) { mutableStateOf(keys.last()) }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Graded price history", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Pills(keys.map { it to (if (it == "Grade 9") "PSA 9" else if (it == "Ungraded") "Raw" else it) }, k, { k = it })
    }
    Spacer(Modifier.height(8.dp))
    LineChart(g.history[k]!!.map { ChartPoint(it.first, it.second) }, height = 150.dp, color = Vault.series2,
        fmtValue = { Graph.money.short(it) }, fmtTime = { fmtDay(it, long = true) })
}

@Composable
private fun WatchDialog(card: Card, variant: String, price: Double, watch: Watch?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(watch?.target?.let { "%.2f".format(it) } ?: "%.2f".format(price * 1.2)) }
    var above by remember { mutableStateOf(watch?.above ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price alert") },
        text = {
            Column {
                Text("Get a notification when ${card.name} (${card.variantLabel(variant)}) is ${if (above) "at or above" else "at or below"} your target. Now ${Graph.money.fmt(price)}.", color = Vault.text2)
                Spacer(Modifier.height(10.dp))
                Pills(listOf(true to "Rises to", false to "Drops to"), above, { above = it })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(text, { text = it }, label = { Text("Target (USD)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                text.toDoubleOrNull()?.let { Graph.store.putWatch(Watch(card.key, variant, it, above, System.currentTimeMillis(), 0)) }
                Graph.sounds.play("select"); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            if (watch != null) TextButton(onClick = { Graph.store.deleteWatch(card.key); onDismiss() }) { Text("Remove alert") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
