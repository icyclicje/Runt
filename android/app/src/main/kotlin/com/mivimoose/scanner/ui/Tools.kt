package com.mivimoose.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.CardSet
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.packs.PackModel
import com.mivimoose.core.vision.CardFinder
import com.mivimoose.core.vision.Centering
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes
import com.mivimoose.scanner.data.Money
import com.mivimoose.scanner.scan.yPlane
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

private class ToolDef(val id: String, val title: String, val blurb: String, val icon: ImageVector)

private val TOOLS = listOf(
    ToolDef("ev", "Pack value", "What a pack or box is worth on average, and your chance of a hit", Icons.Default.Calculate),
    ToolDef("pullrates", "Pull rates", "Odds for every rarity and card, with sources", Icons.Default.Percent),
    ToolDef("simulator", "Rip packs", "Open virtual packs from any set", Icons.Default.Casino),
    ToolDef("grading", "Grading calculator", "Is it worth sending to PSA?", Icons.Default.Verified),
    ToolDef("centering", "Centering checker", "Measure a card's centering with the camera", Icons.Default.CenterFocusStrong),
    ToolDef("trade", "Trade calculator", "Compare both sides of a trade", Icons.Default.Balance),
    ToolDef("fees", "Seller fees", "What you keep after marketplace fees", Icons.Default.Sell),
    ToolDef("bulk", "Bulk value", "Value a box of commons and holos", Icons.Default.Inventory2),
    ToolDef("binder", "Binder planner", "Pages needed for a set", Icons.Default.MenuBook),
    ToolDef("currency", "Currency", "Convert card prices", Icons.Default.CurrencyExchange),
    ToolDef("codes", "Set code lookup", "Which set is \"PAF\" or \"SV2a\"?", Icons.Default.QrCode2),
    ToolDef("share", "Share picture", "Make an image of your collection or pulls", Icons.Default.IosShare),
)

@Composable
fun ToolsScreen(nav: NavHostController) {
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = BottomBarSpace + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Column { Text("Tools", style = MaterialTheme.typography.headlineMedium); Text("For collectors, rippers and traders", color = Vault.text2); Spacer(Modifier.height(4.dp)) }
        }
        items(TOOLS, key = { it.id }) { t ->
            Glass(Modifier.fillMaxWidth().height(138.dp), onClick = { nav.navigate(if (t.id == "share") Routes.share() else Routes.tool(t.id)) }) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Vault.surface3), contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, tint = Vault.mint)
                }
                Spacer(Modifier.height(10.dp))
                Text(t.title, fontWeight = FontWeight.SemiBold)
                Text(t.blurb, color = Vault.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ToolScreen(vm: AppVM, nav: NavHostController, id: String, setArg: String?) {
    val t = TOOLS.firstOrNull { it.id == id } ?: return
    val cat by vm.catalog.collectAsState()
    Graph.catalog.priceTick.collectAsState().value
    var setId by rememberSaveable { mutableStateOf(setArg ?: Graph.prefs.value.focusSetId ?: cat?.order?.firstOrNull { cat?.set(it)?.lang == "en" && cat?.set(it)?.source == null }) }
    var picking by remember { mutableStateOf(false) }
    val set = setId?.let { cat?.set(it) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(t.title, style = MaterialTheme.typography.titleLarge)
        }
        val needsSet = id in setOf("ev", "pullrates", "simulator", "binder")
        Column(Modifier.fillMaxSize().then(if (id == "centering") Modifier else Modifier.verticalScroll(rememberScrollState())).padding(horizontal = 16.dp).padding(bottom = if (id == "centering") 0.dp else BottomBarSpace)) {
            if (needsSet) {
                SetChooser(set) { picking = true }
                Spacer(Modifier.height(12.dp))
            }
            when (id) {
                "ev" -> set?.let { EvTool(it, nav) }
                "pullrates" -> set?.let { PullRatesTool(it) }
                "simulator" -> set?.let { SimulatorTool(it) }
                "grading" -> GradingTool()
                "centering" -> CenteringTool()
                "trade" -> TradeTool()
                "fees" -> FeesTool()
                "bulk" -> BulkTool()
                "binder" -> set?.let { BinderTool(it) }
                "currency" -> CurrencyTool()
                "codes" -> CodesTool(nav)
            }
        }
    }
    if (picking) SetPicker(onPick = { setId = it; picking = false }, onDismiss = { picking = false })
}

@Composable
private fun SetChooser(set: CardSet?, onClick: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (set?.logo != null) AsyncImage(set.logo, null, Modifier.size(width = 64.dp, height = 30.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("SET", style = Eyebrow)
                Text(set?.name ?: "Choose a set", fontWeight = FontWeight.SemiBold)
            }
            Text("Change", color = Vault.mint, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private fun String.num() = replace(",", ".").toDoubleOrNull() ?: 0.0

// ---------------------------------------------------------------- pack value

@Composable
private fun EvTool(set: CardSet, nav: NavHostController) {
    val pm = Graph.catalog.packs
    LaunchedEffect(set.id) { Graph.catalog.refreshSet(set.id, 360); Graph.prices.refreshPack(set) }
    Graph.catalog.priceTick.collectAsState().value
    val ev = pm.expectedValue(set)
    val prods = pm.products(set)
    val packPrice = set.sealedPrice ?: prods.first().price
    var threshold by rememberSaveable { mutableStateOf(20f) }
    Glass(Modifier.fillMaxWidth()) {
        Text("AVERAGE VALUE OF ONE PACK", style = Eyebrow)
        Text(Graph.money.fmt(ev), style = MaterialTheme.typography.displaySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pq = Graph.prices.packQuote(set.id)
            Text("Pack price ${Graph.money.fmt(packPrice)}" + when {
                pq != null && pq.basis == "sales" -> " (live: last ${pq.sales.size} sales)"
                pq != null && pq.basis == "listing" -> " (live: cheapest for sale)"
                set.sealedPrice != null -> " (market)"
                else -> " (estimated)"
            }, color = Vault.text2, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp)); Delta(ev / packPrice - 1, small = true)
        }
        Text(if (ev >= packPrice) "On average, packs are worth more than they cost." else "On average you get back %.0f%% of what a pack costs.".format(ev / packPrice * 100), color = Vault.muted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Text("CHANCE OF A HIT", style = Eyebrow)
        Text("Card worth ${Graph.money.fmt(threshold.toDouble(), cents = false)} or more: %.1f%% per pack".format(pm.hitChance(set, threshold.toDouble()) * 100), fontWeight = FontWeight.SemiBold)
        Slider(threshold, { threshold = it }, valueRange = 5f..300f)
        val box = prods.last()
        val pBox = 1 - Math.pow(1 - pm.hitChance(set, threshold.toDouble()), box.packs.toDouble())
        Text("In a ${box.label.lowercase()} of ${box.packs} packs: %.0f%%".format(pBox * 100), color = Vault.text2, fontSize = 13.sp)
        if (pm.godPackOdds(set) > 0) Text("God pack: 1 in ${"%,d".format((1 / pm.godPackOdds(set)).toLong())} packs", color = Vault.orchid, fontSize = 13.sp)
    }
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Text("PRODUCTS", style = Eyebrow)
        for (p in prods) {
            val value = ev * p.packs
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(p.label, fontWeight = FontWeight.SemiBold); Text("${p.packs} ${if (p.packs == 1) "pack" else "packs"} · avg ${Graph.money.fmt(value, cents = false)}", color = Vault.muted, fontSize = 12.sp) }
                val price = if (p.key == "pack") packPrice else p.price
                Text(Graph.money.fmt(price, cents = false)); Spacer(Modifier.width(8.dp)); Delta(value / price - 1, small = true)
            }
        }
        Text("Box and bundle prices are estimated from the pack price.", color = Vault.muted, fontSize = 11.sp)
    }
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Text("WHERE THE VALUE COMES FROM", style = Eyebrow)
        val rows = pm.rarityTable(set).map { it to it.odds * it.value }.sortedByDescending { it.second }
        val total = rows.sumOf { it.second }.coerceAtLeast(1e-9)
        for ((r, contrib) in rows.take(10)) {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.rarity, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Graph.money.fmt(contrib), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("  %.0f%%".format(contrib / total * 100), color = Vault.muted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
            }
            SegmentBar(listOf(contrib to Vault.series1, (total - contrib) to Vault.surface3), height = 4.dp)
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionTitle("Chase cards")
    for (c in set.cards.sortedByDescending { it.price }.take(8)) {
        val odds = pm.cardOdds(set, c)
        Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.card(c.key)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CardImage(c, Modifier.width(40.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(c.rarity, color = Vault.muted, fontSize = 12.sp) }
            Column(horizontalAlignment = Alignment.End) {
                Text(Graph.money.fmt(c.price), fontWeight = FontWeight.Bold)
                if (odds > 0) Text("1 in ${"%,d".format((1 / odds).toLong())}", color = Vault.muted, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- pull rates

@Composable
private fun PullRatesTool(set: CardSet) {
    val pm = Graph.catalog.packs
    val info = pm.rateInfo(set)
    val src = pm.rateSource(set)
    Glass(Modifier.fillMaxWidth()) {
        Text("SOURCE", style = Eyebrow)
        Text(when (src) {
            "documented" -> "Measured from real openings"
            "box" -> "Estimated from box guarantees (Japanese-style boxes)"
            "series" -> "Average of measured sets in this series"
            else -> "Estimated for this era"
        }, fontWeight = FontWeight.SemiBold)
        info?.let { i ->
            Text(listOfNotNull(i.packsSampled?.let { "${"%,d".format(it)} packs" }, i.confidence.takeIf { it.isNotBlank() }?.let { "$it confidence" }).joinToString(" · "), color = Vault.text2, fontSize = 13.sp)
            if (i.note.isNotBlank()) Text(i.note, color = Vault.muted, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            val ctx = LocalContext.current
            i.source.split(" ; ", ";").map { it.trim() }.filter { it.startsWith("http") }.take(3).forEach { url ->
                Text(url.removePrefix("https://").removePrefix("www.").take(48), color = Vault.mint, fontSize = 12.sp, modifier = Modifier.clickable { open(ctx, url) }.padding(vertical = 2.dp))
            }
        }
        Text("${pm.packSize(set)} cards per pack" + if (pm.godPackOdds(set) > 0) " · god pack 1 in ${"%,d".format((1 / pm.godPackOdds(set)).toLong())}" else "", color = Vault.text2, fontSize = 13.sp)
    }
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Row { Text("RARITY", Modifier.weight(1f), style = Eyebrow); Text("CARDS", Modifier.width(48.dp), style = Eyebrow); Text("ANY ONE", Modifier.width(84.dp), style = Eyebrow); Text("ONE CARD", Modifier.width(84.dp), style = Eyebrow) }
        for (r in pm.rarityTable(set)) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.rarity, Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, color = Vault.tierColor(if (r.tier >= 5) 3 else if (r.tier == 4) 2 else if (r.tier == 3) 1 else 0))
                Text("${r.cards}", Modifier.width(48.dp), fontSize = 13.sp)
                Text(oneIn(r.odds), Modifier.width(84.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(oneIn(r.odds / r.cards), Modifier.width(84.dp), fontSize = 13.sp, color = Vault.text2)
            }
        }
        Text("Odds are per pack. \"Any one\" = any card of that rarity.", color = Vault.muted, fontSize = 11.sp)
    }
}

private fun oneIn(p: Double): String = when {
    p <= 0 -> "—"
    p >= 0.95 -> "%.1f/pack".format(p)
    else -> "1 in " + "%,d".format(Math.round(1 / p))
}

// ---------------------------------------------------------------- simulator

@Composable
private fun SimulatorTool(set: CardSet) {
    val pm = Graph.catalog.packs
    val pulls = remember(set.id) { mutableStateListOf<PackModel.Pull>() }
    var god by remember { mutableStateOf(false) }
    var opened by rememberSaveable(set.id) { mutableStateOf(0) }
    var total by rememberSaveable(set.id) { mutableStateOf(0.0) }
    var best by remember(set.id) { mutableStateOf<PackModel.Pull?>(null) }
    var seed by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val price = set.sealedPrice ?: pm.products(set).first().price
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("OPENED", "$opened", Modifier.weight(1f))
        StatTile("PULLED", Graph.money.fmt(total, cents = false), Modifier.weight(1f))
        StatTile("SPENT", Graph.money.fmt(opened * price, cents = false), Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            val (p, g) = pm.open(set, Random(System.nanoTime()))
            pulls.clear(); god = g; seed = System.nanoTime(); opened++; total += p.sumOf { it.value }
            p.maxByOrNull { it.value }?.let { b -> if (best == null || b.value > best!!.value) best = b }
            scope.launch {
                Graph.sounds.play("deal")
                for (x in p) { delay(260); pulls += x; Graph.sounds.play(if (x.tier >= 5) "jackpot" else if (x.tier == 4) "big" else if (x.tier == 3) "hit" else "click") }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Vault.mint, contentColor = Vault.bg), modifier = Modifier.weight(1f)) { Text("Open a pack", fontWeight = FontWeight.Bold) }
        OutlinedButton(onClick = { pulls.clear(); opened = 0; total = 0.0; best = null }) { Text("Reset") }
    }
    if (god) HoloText("GOD PACK!", MaterialTheme.typography.headlineMedium, Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(12.dp))
    Box {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in pulls.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in row) FlipIn(p, Modifier.weight(1f))
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (pulls.any { it.tier >= 5 }) Burst(seed, count = 90)
    }
    best?.let { b ->
        Spacer(Modifier.height(12.dp))
        Text("Best pull so far: ${b.card.name} ${Graph.money.fmt(b.value)}", color = Vault.text2)
    }
}

@Composable
private fun FlipIn(p: PackModel.Pull, modifier: Modifier) {
    val rot = remember(p) { Animatable(90f) }
    LaunchedEffect(p) { rot.animateTo(0f, tween(320)) }
    val tier = if (p.tier >= 5) 3 else if (p.tier == 4) 2 else if (p.tier == 3) 1 else 0
    Column(modifier.graphicsLayer { rotationY = rot.value; cameraDistance = 12f * density }) {
        Box {
            CardImage(p.card, Modifier.fillMaxWidth().tierGlow(tier, RoundedCornerShape(6.dp)))
            if (p.reverse || tier > 0) HoloSheen(Modifier.matchParentSize(), alpha = if (p.reverse) 0.2f else 0.35f, shape = RoundedCornerShape(6.dp))
        }
        Text(Graph.money.fmt(p.value), color = if (tier > 0) Vault.tierColor(tier) else Vault.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (p.reverse) Text("Reverse holo", color = Vault.muted, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------- grading

@Composable
private fun GradingTool() {
    var card by remember { mutableStateOf<Card?>(null) }
    var raw by rememberSaveable { mutableStateOf("") }
    var p10 by rememberSaveable { mutableStateOf("") }
    var p9 by rememberSaveable { mutableStateOf("") }
    var gem by rememberSaveable { mutableStateOf("") }
    var fee by rememberSaveable { mutableStateOf("25") }
    var ship by rememberSaveable { mutableStateOf("10") }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(card) {
        val c = card ?: return@LaunchedEffect
        raw = "%.2f".format(c.price)
        Graph.prices.graded(c, c.variants().first().id, Graph.catalog.catalog.value?.set(c.setId))?.let { g ->
            g.psa10?.let { p10 = "%.2f".format(it) }; g.psa9?.let { p9 = "%.2f".format(it) }; g.gemRate?.let { gem = "%.0f".format(it * 100) }
        }
    }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text(card?.let { "${it.name} · ${it.setName}" } ?: "Pick a card to fill in prices") }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Raw price $", raw, { raw = it }, Modifier.weight(1f)); NumberField("Gem rate %", gem, { gem = it }, Modifier.weight(1f)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("PSA 10 $", p10, { p10 = it }, Modifier.weight(1f)); NumberField("PSA 9 $", p9, { p9 = it }, Modifier.weight(1f)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Grading fee $", fee, { fee = it }, Modifier.weight(1f)); NumberField("Shipping $", ship, { ship = it }, Modifier.weight(1f)) }
    Spacer(Modifier.height(12.dp))
    val g = (gem.num() / 100).coerceIn(0.0, 1.0)
    // Below a 10, most modern cards come back 9; assume the rest split 9 / 8 (8 at ~raw).
    val pNine = (1 - g) * 0.7
    val pLow = 1 - g - pNine
    val expected = g * p10.num() + pNine * p9.num() + pLow * raw.num() * 0.9
    val cost = fee.num() + ship.num()
    val profit = expected - cost - raw.num()
    Glass(Modifier.fillMaxWidth()) {
        Text("EXPECTED VALUE GRADED", style = Eyebrow)
        Text(Graph.money.fmt(expected), style = MaterialTheme.typography.headlineMedium)
        Text("vs ${Graph.money.fmt(raw.num())} raw + ${Graph.money.fmt(cost)} grading", color = Vault.text2, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (profit >= 0) "Worth grading: " else "Not worth it: ", fontWeight = FontWeight.Bold, color = if (profit >= 0) Vault.goodText else Vault.badText)
            Text((if (profit >= 0) "+" else "") + Graph.money.fmt(profit) + " expected", fontWeight = FontWeight.SemiBold)
        }
        val be = if (p10.num() - p9.num() > 0) ((cost + raw.num() - 0.7 * p9.num() - 0.3 * raw.num() * 0.9) / (p10.num() - 0.7 * p9.num() - 0.3 * raw.num() * 0.9)) else null
        be?.let { Text("Break-even gem rate: %.0f%%".format((it * 100).coerceIn(0.0, 100.0)), color = Vault.muted, fontSize = 12.sp) }
        Text("Assumes non-10s come back PSA 9 70% of the time. Fees change: check PSA's current prices.", color = Vault.muted, fontSize = 11.sp)
    }
    if (picking) CardPicker({ card = it; picking = false }, { picking = false })
}

@Composable
fun CardPicker(onPick: (Card) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val cat = Graph.catalog.catalog.value ?: return
    val list = rememberSearch(q, limit = 40)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Find a card") }, text = {
        Column {
            SearchField(q, { q = it }, "Name, number or set")
            LazyColumn(Modifier.heightIn(max = 420.dp)) { items(list, key = { it.key }) { c -> CardRow(c) { onPick(c) } } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---------------------------------------------------------------- centering

@Composable
private fun CenteringTool() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var res by remember { mutableStateOf<Centering.Result?>(null) }
    val history = remember { ArrayDeque<Centering.Result>() }
    var frozen by remember { mutableStateOf(false) }
    Text("Lay the card flat on a dark surface and hold the phone straight above it.", color = Vault.text2, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().aspectRatio(9f / 13f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
        if (granted) CenteringCamera { gray ->
            if (frozen) return@CenteringCamera
            val o = CardFinder.find(gray, 2, 0.08f).firstOrNull() ?: return@CenteringCamera
            Centering.measure(gray, o.quad)?.let { r ->
                history.addLast(r); while (history.size > 8) history.removeFirst()
                val n = history.size
                res = Centering.Result(history.sumOf { it.left.toDouble() }.toFloat() / n, history.sumOf { it.right.toDouble() }.toFloat() / n,
                    history.sumOf { it.top.toDouble() }.toFloat() / n, history.sumOf { it.bottom.toDouble() }.toFloat() / n, history.minOf { it.confidence })
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val r = res
    Glass(Modifier.fillMaxWidth()) {
        if (r == null) Text("Looking for the card…", color = Vault.muted)
        else {
            Row {
                Column(Modifier.weight(1f)) { Text("LEFT / RIGHT", style = Eyebrow); Text(r.split(r.lr), style = MaterialTheme.typography.headlineMedium) }
                Column(Modifier.weight(1f)) { Text("TOP / BOTTOM", style = Eyebrow); Text(r.split(r.tb), style = MaterialTheme.typography.headlineMedium) }
            }
            Text("Front centering allows up to PSA ${r.psaCap}", fontWeight = FontWeight.SemiBold, color = if (r.psaCap >= 10) Vault.goodText else if (r.psaCap >= 9) Vault.mint else Vault.warn)
            if (r.confidence < 0.25f) Text("Borders are hard to see (full-art card or blur): treat as rough.", color = Vault.warn, fontSize = 12.sp)
            Text("PSA 10 needs 55/45 or better on the front; 9 allows 60/40.", color = Vault.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { frozen = !frozen }) { Text(if (frozen) "Measure again" else "Hold this reading") }
    }
}

@Composable
private fun CenteringCamera(onFrame: (org.opencv.core.Mat) -> Unit) {
    val ctx = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val view = remember { androidx.camera.view.PreviewView(ctx).apply { scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER } }
    androidx.compose.runtime.DisposableEffect(owner) {
        val f = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        var provider: androidx.camera.lifecycle.ProcessCameraProvider? = null
        f.addListener({
            runCatching {
                val p = f.get(); provider = p
                val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val an = androidx.camera.core.ImageAnalysis.Builder().setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                an.setAnalyzer(exec) { img -> try { val m = yPlane(img); onFrame(m); m.release() } finally { img.close() } }
                p.unbindAll(); p.bindToLifecycle(owner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, an)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { provider?.unbindAll(); exec.shutdown() }
    }
    androidx.compose.ui.viewinterop.AndroidView({ view }, Modifier.fillMaxSize())
}

// ---------------------------------------------------------------- trade

@Composable
private fun TradeTool() {
    val give = remember { mutableStateListOf<Card>() }
    val get = remember { mutableStateListOf<Card>() }
    var picking by remember { mutableStateOf<Int?>(null) }
    val a = give.sumOf { it.price }; val b = get.sumOf { it.price }
    Glass(Modifier.fillMaxWidth()) {
        Row {
            Column(Modifier.weight(1f)) { Text("YOU GIVE", style = Eyebrow); Text(Graph.money.fmt(a), style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Text("YOU GET", style = Eyebrow); Text(Graph.money.fmt(b), style = MaterialTheme.typography.titleLarge) }
        }
        val diff = b - a
        val fair = max(a, b) > 0 && kotlin.math.abs(diff) / max(a, b) <= 0.05
        Text(when { a == 0.0 && b == 0.0 -> "Add cards to both sides"; fair -> "Fair trade (within 5%)"; diff > 0 -> "You gain ${Graph.money.fmt(diff)}"; else -> "You lose ${Graph.money.fmt(-diff)}" },
            fontWeight = FontWeight.SemiBold, color = if (fair) Vault.goodText else if (diff > 0) Vault.mint else Vault.warn)
        if (a + b > 0) SegmentBar(listOf(a to Vault.series1, b to Vault.series2), height = 8.dp, modifier = Modifier.padding(top = 6.dp))
    }
    for ((side, list) in listOf(0 to give, 1 to get)) {
        SectionTitle(if (side == 0) "You give" else "You get", action = "Add card") { picking = side }
        for ((i, c) in list.withIndex()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            CardImage(c, Modifier.width(36.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(c.name, maxLines = 1, fontWeight = FontWeight.SemiBold); Text(c.setName, color = Vault.muted, fontSize = 12.sp, maxLines = 1) }
            Text(Graph.money.fmt(c.price)); IconButton(onClick = { list.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", tint = Vault.muted) }
        }
    }
    picking?.let { side -> CardPicker({ (if (side == 0) give else get) += it; picking = null }, { picking = null }) }
}

// ---------------------------------------------------------------- fees

@Composable
private fun FeesTool() {
    var sale by rememberSaveable { mutableStateOf("50") }
    var market by rememberSaveable { mutableStateOf("tcgplayer") }
    val presets = mapOf("tcgplayer" to Triple("TCGplayer", 13.25, 0.30), "ebay" to Triple("eBay", 13.6, 0.40), "cardmarket" to Triple("Cardmarket", 5.0, 0.0), "mercari" to Triple("Mercari", 10.0, 0.50))
    var pct by rememberSaveable(market) { mutableStateOf(presets[market]!!.second.toString()) }
    var fixed by rememberSaveable(market) { mutableStateOf(presets[market]!!.third.toString()) }
    var ship by rememberSaveable { mutableStateOf("1.00") }
    Pills(presets.map { it.key to it.value.first }, market, { market = it })
    Spacer(Modifier.height(10.dp))
    NumberField("Sale price $", sale, { sale = it }, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Fee %", pct, { pct = it }, Modifier.weight(1f)); NumberField("Per order $", fixed, { fixed = it }, Modifier.weight(1f)); NumberField("Shipping $", ship, { ship = it }, Modifier.weight(1f)) }
    val fees = sale.num() * pct.num() / 100 + fixed.num()
    val net = sale.num() - fees - ship.num()
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Text("YOU KEEP", style = Eyebrow)
        Text(Graph.money.fmt(net), style = MaterialTheme.typography.headlineMedium)
        Text("Fees ${Graph.money.fmt(fees)} · shipping ${Graph.money.fmt(ship.num())}", color = Vault.text2, fontSize = 13.sp)
        Text("To keep ${Graph.money.fmt(sale.num())}, list at ${Graph.money.fmt((sale.num() + fixed.num() + ship.num()) / (1 - pct.num() / 100))}", color = Vault.muted, fontSize = 12.sp)
        Text("Typical rates, editable: marketplaces change their fees.", color = Vault.muted, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------- bulk

@Composable
private fun BulkTool() {
    val rows = listOf(
        Triple("Commons & uncommons", "1000", "0.005"), Triple("Reverse holos", "50", "0.05"), Triple("Holo rares", "20", "0.10"),
        Triple("ex / V / GX", "10", "0.25"), Triple("Full art / illustration", "2", "1.00"), Triple("Energy", "100", "0.002"),
    )
    val counts = remember { mutableStateListOf(*rows.map { it.second }.toTypedArray()) }
    val rates = remember { mutableStateListOf(*rows.map { it.third }.toTypedArray()) }
    for ((i, r) in rows.withIndex()) {
        Text(r.first, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Count", counts[i], { counts[i] = it }, Modifier.weight(1f))
            NumberField("Each $", rates[i], { rates[i] = it }, Modifier.weight(1f))
        }
    }
    val total = rows.indices.sumOf { counts[it].num() * rates[it].num() }
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Text("BULK VALUE", style = Eyebrow)
        Text(Graph.money.fmt(total), style = MaterialTheme.typography.headlineMedium)
        Text("${"%,d".format(rows.indices.sumOf { counts[it].num().toInt() })} cards at typical buylist rates (editable)", color = Vault.muted, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- binder

@Composable
private fun BinderTool(set: CardSet) {
    var pockets by rememberSaveable { mutableStateOf(9) }
    var master by rememberSaveable { mutableStateOf(false) }
    Pills(listOf(4 to "4-pocket", 9 to "9-pocket", 12 to "12-pocket", 16 to "16-pocket"), pockets, { pockets = it })
    Spacer(Modifier.height(8.dp))
    Pills(listOf(false to "Main set", true to "Master set"), master, { master = it })
    val n = if (master) set.cards.size + set.cards.count { it.hasReverse } + set.cards.sumOf { it.extraVariants.size } else set.cards.count { it.mainSet }
    val sides = (n + pockets - 1) / pockets
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Text("YOU NEED", style = Eyebrow)
        Text("${(sides + 1) / 2} pages", style = MaterialTheme.typography.headlineMedium)
        Text("$n cards · $sides page sides of $pockets pockets (both sides used)", color = Vault.text2, fontSize = 13.sp)
        if (master) Text("Master set counts reverse holos and pattern printings.", color = Vault.muted, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- currency

@Composable
private fun CurrencyTool() {
    var amount by rememberSaveable { mutableStateOf("100") }
    var to by rememberSaveable { mutableStateOf(Graph.prefs.value.currency.takeIf { it != "USD" } ?: "EUR") }
    NumberField("US dollars", amount, { amount = it }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Pills(Money.SYMBOLS.keys.map { it to it }, to, { to = it })
    Spacer(Modifier.height(12.dp))
    Glass(Modifier.fillMaxWidth()) {
        Text(Graph.money.fmt(amount.num(), to), style = MaterialTheme.typography.headlineMedium)
        Text("1 USD = %.4f %s".format(Graph.money.convert(1.0, to), to), color = Vault.muted, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- set codes

@Composable
private fun CodesTool(nav: NavHostController) {
    var q by rememberSaveable { mutableStateOf("") }
    val cat = Graph.catalog.catalog.value ?: return
    SearchField(q, { q = it }, "Set code, e.g. MEW, PAF, SV2a")
    Spacer(Modifier.height(10.dp))
    val hits = if (q.length < 2) emptyList() else {
        val u = q.trim().uppercase()
        (cat.byCode[u] ?: emptyList()) + cat.sets.values.filter { it.code?.uppercase()?.startsWith(u) == true && it.code?.uppercase() != u } +
            cat.sets.values.filter { Catalog.fold(it.name).contains(Catalog.fold(q)) }.take(10)
    }.distinctBy { it.id }
    for (s in hits.take(30)) { SetRow(s, null) { nav.navigate(Routes.set(s.id)) }; Spacer(Modifier.height(8.dp)) }
    if (q.length < 2) Text("Modern cards print the set code next to the card number (bottom left). Japanese codes look like SV2a, M2a.", color = Vault.muted, fontSize = 13.sp)
}
