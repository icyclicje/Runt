package com.mivimoose.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.LANGUAGES
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes
import com.mivimoose.scanner.data.GradedInfo
import com.mivimoose.scanner.scan.CameraFeed
import com.mivimoose.scanner.scan.FileFeed
import com.mivimoose.scanner.scan.LiveScan
import com.mivimoose.scanner.scan.ScanEngine
import com.mivimoose.scanner.scan.ScanResult
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ScanScreen(vm: AppVM, nav: NavHostController, testFrames: String?) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted && testFrames == null) ask.launch(Manifest.permission.CAMERA) }
    val cat by vm.catalog.collectAsState()
    val settings by vm.settings.collectAsState()
    val engine = remember { ScanEngine() }
    DisposableEffect(engine) { onDispose { engine.close() } }
    LaunchedEffect(cat, settings.scanMode, settings.packSetId) { engine.updatePool() }
    val live by engine.live.collectAsState()
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var jackpot by remember { mutableStateOf<ScanResult?>(null) }
    var burst by remember { mutableLongStateOf(0L) }
    var shakeSeed by remember { mutableLongStateOf(0L) }
    var torch by remember { mutableStateOf(false) }
    var nextVariant by remember { mutableStateOf<String?>(null) }
    var pickSet by remember { mutableStateOf(false) }
    val sessionStart = remember { System.currentTimeMillis() }
    val scans by vm.scans.collectAsState()
    val refProgress by Graph.refs.progress.collectAsState()
    LaunchedEffect(engine) {
        engine.results.collect { r ->
            result = r
            nextVariant = null
            if (!r.ignored && r.tier == 3) { jackpot = r; shakeSeed = System.nanoTime() }
            else if (!r.ignored && r.tier == 2) { burst = System.nanoTime(); shakeSeed = System.nanoTime() }
        }
    }
    val shake = rememberShake(shakeSeed, if (result?.tier == 3) 18f else 8f)

    Box(Modifier.fillMaxSize().background(Color.Black).offset { IntOffset(shake.roundToInt(), 0) }) {
        when {
            testFrames != null -> FileFeed(engine, testFrames, Modifier.fillMaxSize())
            granted -> CameraFeed(engine, torch, Modifier.fillMaxSize())
            else -> NoCamera { ask.launch(Manifest.permission.CAMERA) }
        }
        ScanOverlay(live, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") { nav.popBackStack() }
                Spacer(Modifier.weight(1f))
                LanguageButton(settings.scanLanguage)
                RoundIcon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch") { torch = !torch }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Pills(listOf("collect" to "Collect", "pack" to "Pack", "check" to "Price check"), settings.scanMode, { m ->
                    Graph.prefs.update { it.copy(scanMode = m) }
                    if (m == "pack" && settings.packSetId == null) pickSet = true
                })
            }
            if (settings.scanMode == "pack") PackBar(engine, settings.packSetId, result) { pickSet = true }
            Spacer(Modifier.height(8.dp))
            HintPill(live, refProgress?.let { "Loading ${cat?.set(it.setId)?.name ?: "set"} ${it.done}/${it.total}" })
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            AnimatedVisibility(result != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                result?.let { r ->
                    ResultCard(r, engine, nav,
                        onChange = { result = it },
                        onClose = { result = null })
                }
            }
            NextPrinting(nextVariant) { v -> nextVariant = v; engine.nextVariant = v }
            val session = scans.filter { it.at >= sessionStart }
            SessionStrip(session.mapNotNull { s -> cat?.card(s.cardKey)?.let { it to s.price } }, nav)
        }
        if (burst != 0L) Burst(burst, count = 70, sparkles = true)
        jackpot?.let { j -> JackpotOverlay(j.scanId + burst, Graph.money.fmt(j.price), j.card.name) { jackpot = null } }
    }
    if (pickSet) SetPicker(onPick = { id ->
        val s = cat?.set(id)
        Graph.prefs.update { it.copy(packSetId = id, packCost = s?.sealedPrice ?: s?.let { x -> Graph.catalog.packs.products(x).first().price }, scanMode = "pack") }
        engine.nextPack()
        pickSet = false
    }, onDismiss = { pickSet = false })
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LanguageButton(current: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Translate, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (current == "auto") "AUTO" else com.mivimoose.core.catalog.LANGUAGE_FLAGS[current] ?: current, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Automatic") }, { Graph.prefs.update { it.copy(scanLanguage = "auto") }; open = false })
            for ((k, v) in LANGUAGES.filterKeys { it in setOf("en", "ja", "ko", "zh-tw", "zh-cn", "th", "id") }) {
                DropdownMenuItem({ Text(v) }, { Graph.prefs.update { s -> s.copy(scanLanguage = k) }; open = false })
            }
        }
    }
}

/** Card guide, the live outline of the card being read, and its progress. */
@Composable
private fun ScanOverlay(live: LiveScan, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val vw = with(density) { maxWidth.toPx() }; val vh = with(density) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            // guide: a card-shaped frame with corner brackets
            val gw = size.width * 0.74f; val gh = gw * 88f / 63f
            val gx = (size.width - gw) / 2; val gy = (size.height - gh) / 2 - size.height * 0.04f
            val arm = gw * 0.12f
            val col = if (live.quad == null) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.25f)
            val st = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            for ((cx, cy, sx, sy) in listOf(floatArrayOf(gx, gy, 1f, 1f), floatArrayOf(gx + gw, gy, -1f, 1f), floatArrayOf(gx, gy + gh, 1f, -1f), floatArrayOf(gx + gw, gy + gh, -1f, -1f))) {
                val p = Path().apply { moveTo(cx, cy + sy * arm); lineTo(cx, cy); lineTo(cx + sx * arm, cy) }
                drawPath(p, col, style = st)
            }
            val q = live.quad ?: return@Canvas
            if (live.frameW == 0) return@Canvas
            val s = max(vw / live.frameW, vh / live.frameH)
            val dx = (vw - live.frameW * s) / 2; val dy = (vh - live.frameH * s) / 2
            fun pt(i: Int) = Offset(q[2 * i] * live.frameW * s + dx, q[2 * i + 1] * live.frameH * s + dy)
            val path = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1..3) lineTo(pt(i).x, pt(i).y); close() }
            val brush = Brush.linearGradient(Vault.holo, pt(0), pt(2))
            if (live.state == "locked") drawPath(path, Vault.mint.copy(alpha = 0.10f))
            drawPath(path, brush.let { it }, style = Stroke(8.dp.toPx(), join = StrokeJoin.Round), alpha = 0.25f)
            drawPath(path, brush, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
            if (live.progress > 0f && live.state != "locked") {
                val c = pt(0); val r = 14.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.5f), r + 4.dp.toPx(), c)
                drawArc(Vault.mint, -90f, 360f * live.progress, false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun HintPill(live: LiveScan, loading: String?) {
    val text = loading ?: live.reading?.let { "Reading $it…" } ?: live.hint
    if (text.isEmpty()) return
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 7.dp),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PackBar(engine: ScanEngine, setId: String?, last: ScanResult?, onPick: () -> Unit) {
    val set = setId?.let { Graph.catalog.catalog.value?.set(it) }
    val (count, value, _) = remember(last) { engine.packState() }
    val size = set?.let { Graph.catalog.packs.packSize(it) } ?: 10
    val cost = Graph.prefs.value.packCost
    Glass(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onPick)) {
                Text("OPENING ▾", style = Eyebrow)
                Text(set?.name ?: "Choose the set", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$count / $size cards", fontWeight = FontWeight.Bold)
                Text(Graph.money.fmt(value) + (cost?.let { " of " + Graph.money.fmt(it) } ?: ""), color = if (cost != null && value >= cost) Vault.goodText else Vault.text2, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { engine.nextPack() }) { Text("Next pack") }
        }
        Spacer(Modifier.height(6.dp))
        SegmentBar(listOf(count.toDouble() to Vault.mint, (size - count).coerceAtLeast(0).toDouble() to Vault.surface3), height = 5.dp)
    }
}

@Composable
private fun NextPrinting(selected: String?, onSelect: (String?) -> Unit) {
    val opts = listOf<Pair<String?, String>>(null to "Normal", "reverse" to "Reverse", "pokeball" to "Poké Ball", "masterball" to "Master Ball", "first" to "1st Ed.")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("NEXT", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(opts) { (v, label) ->
                val on = v == selected
                Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (on) Vault.mint else Color.Black.copy(alpha = 0.5f))
                    .border(1.dp, if (on) Color.Transparent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                    .clickable { Graph.sounds.play("click"); onSelect(if (on) null else v) }.padding(horizontal = 11.dp, vertical = 6.dp),
                    color = if (on) Vault.bg else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SessionStrip(items: List<Pair<Card, Double>>, nav: NavHostController) {
    Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(86.dp)) {
            Text("SESSION", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(Graph.money.fmt(items.sumOf { it.second }, cents = false), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("${items.size} cards", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.take(30)) { (c, price) ->
                val tier = tierOf(price)
                Column(Modifier.width(46.dp).clickable { nav.navigate(Routes.card(c.key)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    CardImage(c, Modifier.fillMaxWidth().tierGlow(tier, RoundedCornerShape(4.dp), small = true))
                    Text(Graph.money.short(price), color = if (tier > 0) Vault.tierColor(tier) else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** What was just scanned: price, value tier, duplicate status, PSA population, quick fixes. */
@Composable
private fun ResultCard(r: ScanResult, engine: ScanEngine, nav: NavHostController, onChange: (ScanResult) -> Unit, onClose: () -> Unit) {
    val tier = if (r.ignored) 0 else r.tier
    val shape = RoundedCornerShape(24.dp)
    var graded by remember(r.card.key, r.variant) { mutableStateOf(Graph.prices.cachedGraded(r.card, r.variant)) }
    var trend by remember(r.card.key, r.variant) { mutableStateOf<Double?>(null) }
    var showAlts by remember(r.scanId) { mutableStateOf(false) }
    LaunchedEffect(r.card.key, r.variant) {
        Graph.prices.refreshCard(r.card)
        if (Graph.prefs.value.showGraded && graded == null) graded = Graph.prices.graded(r.card, r.variant, Graph.catalog.catalog.value?.set(r.card.setId))
        Graph.prices.history(r.card, r.variant, "month")?.let { h -> if (h.size >= 2 && h.first().price > 0) trend = (h.last().price - h.first().price) / h.first().price }
    }
    Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().tierGlow(tier, shape)) {
        Column(
            Modifier.clip(shape).background(Brush.verticalGradient(listOf(Vault.surface2, Vault.bg))).border(1.dp, Vault.line, shape)
                .then(if (tier >= 1) Modifier.holoBorderAnimated(2.dp, shape) else Modifier).padding(14.dp),
        ) {
            Row {
                Box(Modifier.width(92.dp)) {
                    CardImage(r.card, Modifier.fillMaxWidth())
                    if (tier >= 1) HoloSheen(Modifier.matchParentSize(), alpha = 0.3f, shape = RoundedCornerShape(6.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusTag(r)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.Close, "Close", tint = Vault.muted, modifier = Modifier.size(20.dp).clickable(onClick = onClose))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(r.card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.card.setName} · #${r.card.number}", color = Vault.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tier > 0) HoloText(Graph.money.fmt(r.price), MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
                        else Text(Graph.money.fmt(r.price), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(8.dp))
                        Delta(trend, small = true)
                    }
                    if (tier > 0) Text(Vault.tierName(tier), color = Vault.tierColor(tier), fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.5.sp)
                }
            }
            val variants = r.card.variants()
            if (variants.size > 1) {
                Spacer(Modifier.height(8.dp))
                Pills(variants.map { it.id to "${it.label} ${Graph.money.short(it.price ?: r.card.price)}" }, r.variant, { onChange(engine.setVariant(r, it)) })
            }
            graded?.let { g -> Spacer(Modifier.height(8.dp)); PsaStrip(g) }
            if (r.ownedBefore > 0 && !r.ignored) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Vault.warn.copy(alpha = 0.14f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Vault.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Duplicate: you already own ${r.ownedBefore}" + if (r.added) " (added another)" else "", color = Vault.warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!r.ignored) OutlinedButton(onClick = { engine.undo(r); onClose() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Undo")
                }
                OutlinedButton(onClick = { showAlts = !showAlts }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Wrong card?") }
                Spacer(Modifier.weight(1f))
                if (!r.added && !r.ignored) Button(onClick = { onChange(engine.addAnyway(r)) }, colors = ButtonDefaults.buttonColors(containerColor = Vault.mint, contentColor = Vault.bg)) { Text("Add") }
                else TextButton(onClick = { nav.navigate(Routes.card(r.card.key)) }) { Text("Details") }
            }
            if (showAlts) Alternatives(r) { right -> showAlts = false; engine.correct(r, right) }
        }
    }
}

@Composable
private fun StatusTag(r: ScanResult) {
    when {
        r.ignored -> Tag("ALREADY IN THIS PACK", Vault.muted)
        r.mode == "check" -> Tag("PRICE CHECK", Vault.periwinkle)
        r.ownedBefore > 0 -> Tag("DUPLICATE", Vault.warn)
        r.added -> Tag("NEW · ADDED", Vault.goodText)
        else -> Tag("NEW", Vault.goodText)
    }
    if (r.packDone) { Spacer(Modifier.width(6.dp)); Tag("PACK DONE ${Graph.money.short(r.packValue)}", Vault.mint) }
}

/** PSA 10 price, population and gem rate, with a mini population chart. */
@Composable
private fun PsaStrip(g: GradedInfo) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Vault.surface3).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PSA 10 " + Graph.money.fmt(g.psa10, cents = (g.psa10 ?: 0.0) < 100), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Pop ${"%,d".format(g.psaTotal)}" + (g.gemRate?.let { " · gem rate %.0f%%".format(it * 100) } ?: "") + (g.psa9?.let { " · PSA 9 " + Graph.money.short(it) } ?: ""),
                color = Vault.text2, fontSize = 12.sp)
        }
        g.psa?.let { pop ->
            val mx = max(1, pop.max())
            Row(Modifier.height(30.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                pop.forEachIndexed { i, v ->
                    Box(Modifier.width(5.dp).height((28f * v / mx).coerceAtLeast(1f).dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(if (i == 9) Vault.series1 else Vault.series1.copy(alpha = 0.45f)))
                }
            }
        }
    }
}

@Composable
private fun Alternatives(r: ScanResult, onPick: (Card) -> Unit) {
    var q by remember { mutableStateOf("") }
    val cat = Graph.catalog.catalog.value ?: return
    val found = rememberSearch(if (q.length >= 2) q else r.card.name, limit = 20)
    val list = if (q.length >= 2) found else r.alternatives.ifEmpty { found.filter { it.key != r.card.key } }
    Column(Modifier.padding(top = 10.dp)) {
        SearchField(q, { q = it }, "Search the right card")
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(list, key = { it.key }) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(c) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardImage(c, Modifier.width(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${c.setName} · #${c.number}", color = Vault.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(Graph.money.fmt(c.price), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun NoCamera(onAsk: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Empty(Icons.Default.CameraAlt, "Camera access needed", "The scanner reads cards with the camera. Nothing is recorded or uploaded.") {
            Button(onClick = onAsk) { Text("Allow camera") }
        }
    }
}

@Composable
fun SetPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val cat = Graph.catalog.catalog.value ?: return
    val sets = cat.order.mapNotNull { cat.set(it) }.filter { !it.firstEdition && (q.isBlank() || com.mivimoose.core.catalog.Catalog.fold("${it.name} ${it.code ?: ""} ${it.nameLocal ?: ""}").contains(com.mivimoose.core.catalog.Catalog.fold(q))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which set are you opening?") },
        text = {
            Column {
                SearchField(q, { q = it }, "Search sets")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sets.take(200), key = { it.id }) { s -> SetRow(s, null) { onPick(s.id) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
