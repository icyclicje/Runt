package com.mivimoose.scanner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mivimoose.core.catalog.Card
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes
import com.mivimoose.scanner.data.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(vm: AppVM, nav: NavHostController) {
    val p by vm.portfolio.collectAsState()
    val snaps by vm.snapshots.collectAsState()
    val scans by vm.scans.collectAsState()
    val packs by vm.packs.collectAsState()
    val cat by vm.catalog.collectAsState()
    val status by vm.status.collectAsState()
    val live by vm.live.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = BottomBarSpace + 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HoloText("MIVIMOOSE RIPS", MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp))
                    Text(status, color = Vault.muted, fontSize = 12.sp)
                }
                IconButton(onClick = { nav.navigate(Routes.share()) }) { Icon(Icons.Default.IosShare, "Share", tint = Vault.text2) }
                IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings", tint = Vault.text2) }
            }
        }
        val pf = p
        if (pf == null || pf.copies == 0) {
            item { Spacer(Modifier.height(30.dp)); Welcome(nav, cat != null) }
            return@LazyColumn
        }
        item {
            Spacer(Modifier.height(10.dp))
            Text("PORTFOLIO VALUE", style = Eyebrow)
            CountUpPrice(pf.total, MaterialTheme.typography.displayMedium)
            val weekAgo = snapshotAt(snaps, 7)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (weekAgo != null && weekAgo.value > 0) {
                    Delta((pf.total - weekAgo.value) / weekAgo.value)
                    Text("  7 days", color = Vault.muted, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                }
                LiveDot(live.running)
                Text(
                    if (live.running) " Updating prices…" else " Live · " + ago(live.updated.takeIf { it > 0 } ?: Graph.catalog.pricesUpdated),
                    color = Vault.text2, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { scope.launch { Graph.prices.refreshCollection(force = true) } }) {
                    Icon(Icons.Default.Refresh, "Refresh prices", tint = Vault.text2, modifier = Modifier.size(20.dp))
                }
            }
        }
        item { ValueHistory(snaps) }
        item {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("CARDS", "%,d".format(pf.copies), Modifier.weight(1f), sub = "${"%,d".format(pf.unique)} unique")
                StatTile("SETS", pf.sets.size.toString(), Modifier.weight(1f), sub = pf.sets.count { it.pct >= 1f }.let { "$it complete" })
                if (pf.cost > 0) {
                    val pl = pf.total - pf.cost
                    StatTile("PROFIT", Graph.money.fmt(pl, cents = false), Modifier.weight(1f), sub = "on ${Graph.money.fmt(pf.cost, cents = false)} paid")
                } else {
                    StatTile("TOP CARD", Graph.money.fmt(pf.holdings.firstOrNull()?.unit, cents = false), Modifier.weight(1f), sub = pf.holdings.firstOrNull()?.card?.name)
                }
            }
        }
        item {
            SectionTitle("Most valuable", Modifier.padding(top = 10.dp), "All cards") { nav.navigate("collection") }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pf.holdings.distinctBy { it.card.key }.take(12), key = { it.card.key }) { h ->
                    CardTile(h.card, h.unit, Modifier.width(118.dp), badge = if (h.owned.qty > 1) "x${h.owned.qty}" else null) { nav.navigate(Routes.card(h.card.key)) }
                }
            }
        }
        if (scans.isNotEmpty()) item {
            SectionTitle("Recent scans", Modifier.padding(top = 14.dp), "See all") { nav.navigate("recent") }
            Glass(padding = PaddingValues(6.dp)) {
                for (s in scans.take(5)) {
                    val card = cat?.card(s.cardKey) ?: continue
                    ScanRowItem(card, s.variant, s.price, s.at, s.duplicate, s.mode) { nav.navigate(Routes.card(card.key)) }
                }
            }
        }
        if (pf.sets.isNotEmpty()) item {
            SectionTitle("Set progress", Modifier.padding(top = 14.dp), "All sets") { nav.navigate("collection") }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (sp in pf.sets.take(4)) SetProgressRow(sp) { nav.navigate(Routes.set(sp.set.id)) }
            }
        }
        if (packs.isNotEmpty() && cat != null) item {
            SectionTitle("Packs opened", Modifier.padding(top = 14.dp), "History") { nav.navigate("packs") }
            PackSummary(packs.size, packs.take(30).sumOf { it.cost ?: 0.0 }, packs.take(30).sumOf { pk -> Graph.store.scansOfPack(pk.id).sumOf { it.price } })
        }
    }
}

@Composable
private fun Welcome(nav: NavHostController, ready: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            HoloSheen(Modifier.size(150.dp, 210.dp), shape = RoundedCornerShape(12.dp), alpha = 0.6f)
            Icon(Icons.Default.DocumentScanner, null, tint = Vault.text, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        HoloText("Scan your first card", MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Point the camera at any Pokémon card: English, Japanese, Korean, Chinese, Thai or Indonesian. " +
                "You get its TCGplayer price, PSA population and a market graph, and it goes into your collection.",
            color = Vault.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { nav.navigate("scan") }, enabled = ready, colors = ButtonDefaults.buttonColors(containerColor = Vault.mint, contentColor = Vault.bg)) {
            Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text("Start scanning", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text("or browse sets in Collection and Market", color = Vault.muted, fontSize = 12.sp)
    }
}

@Composable
fun CountUpPrice(target: Double, style: androidx.compose.ui.text.TextStyle) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target.toFloat(), tween(1100, easing = FastOutSlowInEasing)) }
    Text(Graph.money.fmt(anim.value.toDouble()), style = style, maxLines = 1)
}

@Composable
fun LiveDot(busy: Boolean) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(if (busy) 400 else 1200, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(if (busy) Vault.warn else Vault.goodText))
}

fun ago(t: Long): String {
    if (t <= 0) return "prices from the card database"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "updated just now"; m < 60 -> "updated ${m}m ago"; m < 1440 -> "updated ${m / 60}h ago"; else -> "updated ${m / 1440}d ago" }
}

private fun snapshotAt(snaps: List<Snapshot>, daysAgo: Long): Snapshot? {
    val day = LocalDate.now().minusDays(daysAgo).toString()
    return snaps.lastOrNull { it.day <= day } ?: snaps.firstOrNull()?.takeIf { it.day < LocalDate.now().toString() }
}

@Composable
private fun ValueHistory(snaps: List<Snapshot>) {
    var range by rememberSaveable { mutableStateOf("1M") }
    Glass(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Value history", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pills(listOf("1W" to "1W", "1M" to "1M", "3M" to "3M", "1Y" to "1Y", "All" to "All"), range, { range = it })
        }
        val days = when (range) { "1W" -> 7L; "1M" -> 30L; "3M" -> 90L; "1Y" -> 365L; else -> 100_000L }
        val from = LocalDate.now().minusDays(days).toString()
        val pts = snaps.filter { it.day >= from }.map { ChartPoint(LocalDate.parse(it.day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), it.value) }
        Spacer(Modifier.height(10.dp))
        if (pts.size < 2) {
            Text("Your collection's value is recorded every day. The graph fills in from tomorrow.", color = Vault.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            LineChart(pts, height = 180.dp, fmtValue = { Graph.money.short(it) })
        }
    }
}

/** A card picture with name and price; glows by value tier. */
@Composable
fun CardTile(card: Card, price: Double, modifier: Modifier = Modifier, badge: String? = null, dim: Boolean = false, onClick: () -> Unit) {
    val tier = tierOf(price)
    Column(modifier.clickable { Graph.sounds.play("click"); onClick() }) {
        Box {
            CardImage(card, Modifier.fillMaxWidth().tierGlow(if (dim) 0 else tier, RoundedCornerShape(6.dp)), dim = dim)
            if (tier >= 2 && !dim) HoloSheen(Modifier.matchParentSize(), alpha = 0.22f, shape = RoundedCornerShape(6.dp))
            if (badge != null) Text(badge, Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Vault.bg.copy(alpha = 0.8f)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = Vault.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(card.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (dim) Vault.muted else Vault.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Graph.money.fmt(price), fontSize = 12.sp, color = if (tier > 0 && !dim) Vault.tierColor(tier) else Vault.text2, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text("#${card.number}", fontSize = 11.sp, color = Vault.muted, maxLines = 1)
        }
    }
}

@Composable
fun ScanRowItem(card: Card, variant: String, price: Double, at: Long, dupe: Boolean, mode: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(card, Modifier.width(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${card.setName} · #${card.number}", color = Vault.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (variant != "normal" && variant != "holo") { Spacer(Modifier.width(6.dp)); Tag(card.variantLabel(variant), Vault.periwinkle) }
                if (dupe) { Spacer(Modifier.width(6.dp)); Tag("DUPE", Vault.warn) }
                if (mode == "check") { Spacer(Modifier.width(6.dp)); Tag("CHECK", Vault.muted) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val tier = tierOf(price)
            Text(Graph.money.fmt(price), fontWeight = FontWeight.Bold, color = if (tier > 0) Vault.tierColor(tier) else Vault.text)
            Text(timeAgo(at), color = Vault.muted, fontSize = 11.sp)
        }
    }
}

fun timeAgo(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; m < 43200 -> "${m / 1440}d"; else -> java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
}

@Composable
fun SetProgressRow(sp: com.mivimoose.scanner.data.SetProgress, onClick: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(sp.pct, 54.dp, 5.dp) { Text("${(sp.pct * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sp.set.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (sp.set.lang != "en") { Spacer(Modifier.width(6.dp)); Tag(com.mivimoose.core.catalog.LANGUAGE_FLAGS[sp.set.lang] ?: sp.set.lang.uppercase(), Vault.periwinkle) }
                }
                Text("${sp.ownedMain}/${sp.totalMain} cards · master ${sp.ownedAll}/${sp.totalAll}", color = Vault.text2, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                SegmentBar(listOf(sp.value to Vault.series1, sp.missingValue to Vault.surface3), height = 6.dp)
                Text("${Graph.money.fmt(sp.value, cents = false)} owned · ${Graph.money.fmt(sp.missingValue, cents = false)} to complete", color = Vault.muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PackSummary(n: Int, cost: Double, value: Double) {
    Glass(Modifier.fillMaxWidth()) {
        Row {
            Column(Modifier.weight(1f)) { Text("PACKS", style = Eyebrow); Text("$n", style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f)) { Text("PULLED", style = Eyebrow); Text(Graph.money.fmt(value, cents = false), style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f)) {
                Text("VS PAID", style = Eyebrow)
                if (cost > 0) Delta((value - cost) / cost) else Text("—", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
