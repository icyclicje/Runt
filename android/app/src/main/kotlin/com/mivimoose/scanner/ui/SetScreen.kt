package com.mivimoose.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.LANGUAGE_FLAGS
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes

@Composable
fun SetScreen(vm: AppVM, nav: NavHostController, setId: String) {
    val cat by vm.catalog.collectAsState()
    val p by vm.portfolio.collectAsState()
    Graph.catalog.priceTick.collectAsState().value
    val set = cat?.set(setId) ?: return
    var filter by rememberSaveable { mutableStateOf("all") }
    var view by rememberSaveable { mutableStateOf("grid") }
    LaunchedEffect(setId) { Graph.catalog.refreshSet(setId, 360) }
    val owned = p?.holdings?.filter { it.card.setId == setId }?.groupBy { it.card.key }?.mapValues { e -> e.value.sumOf { it.owned.qty } } ?: emptyMap()
    val sp = p?.sets?.firstOrNull { it.set.id == setId }
    val packs = Graph.catalog.packs
    val cards = set.sortedCards.filter {
        when (filter) {
            "owned" -> it.key in owned
            "missing" -> it.key !in owned
            "hits" -> it.isHit || it.price >= Graph.prefs.value.hitValue
            else -> true
        }
    }.let { l -> if (filter == "value") l.sortedByDescending { it.price } else l }

    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BottomBarSpace + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { nav.navigate(Routes.tool("pullrates") + "?set=" + android.net.Uri.encode(setId)) }) { Icon(Icons.Default.Percent, "Pull rates", tint = Vault.text2) }
                    IconButton(onClick = { nav.navigate(Routes.tool("simulator") + "?set=" + android.net.Uri.encode(setId)) }) { Icon(Icons.Default.Casino, "Open packs", tint = Vault.text2) }
                    IconButton(onClick = { nav.navigate(Routes.share("set", setId)) }) { Icon(Icons.Default.IosShare, "Share", tint = Vault.text2) }
                }
                if (set.logo != null) AsyncImage(set.logo, set.name, Modifier.height(64.dp).fillMaxWidth(), alignment = Alignment.CenterStart)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(set.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, false))
                    if (set.lang != "en") { Spacer(Modifier.width(8.dp)); Tag(LANGUAGE_FLAGS[set.lang] ?: set.lang, Vault.periwinkle) }
                }
                Text(listOfNotNull(set.nameLocal, set.series, set.release, set.code).joinToString(" · "), color = Vault.muted, fontSize = 12.sp)
                if (set.incomplete) Text("This card list may be missing secret rares.", color = Vault.warn, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Glass {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring(sp?.pct ?: 0f, 92.dp, 8.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${((sp?.pct ?: 0f) * 100).toInt()}%", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("set", color = Vault.muted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            KV("Main set", "${sp?.ownedMain ?: 0} / ${set.cards.count { it.mainSet }}")
                            KV("Master set", "${sp?.ownedAll ?: 0} / ${set.cards.size}")
                            KV("Owned value", Graph.money.fmt(sp?.value ?: 0.0, cents = false))
                            KV("To complete", Graph.money.fmt(set.cards.filter { it.key !in owned }.sumOf { it.price }, cents = false))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        val ev = packs.expectedValue(set)
                        val pack = packs.products(set).first()
                        Column(Modifier.weight(1f)) { Text("PACK VALUE", style = Eyebrow); Text(Graph.money.fmt(ev), fontWeight = FontWeight.SemiBold) }
                        LaunchedEffect(set.id) { Graph.prices.refreshPack(set) }
                        Graph.catalog.priceTick.collectAsState().value
                        Column(Modifier.weight(1f)) { Text(if (Graph.prices.packQuote(set.id) != null) "PACK · LIVE" else "PACK PRICE", style = Eyebrow); Text(Graph.money.fmt(set.sealedPrice ?: pack.price), fontWeight = FontWeight.SemiBold) }
                        Column(Modifier.weight(1f)) { Text("HIT ≥ $20", style = Eyebrow); Text("%.0f%%".format(packs.hitChance(set, 20.0) * 100), fontWeight = FontWeight.SemiBold) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pills(listOf("all" to "All", "owned" to "Owned", "missing" to "Missing", "hits" to "Hits", "value" to "By value"), filter, { filter = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Pills(listOf("grid" to "Grid", "binder" to "Binder"), view, { view = it })
            }
        }
        if (view == "binder") {
            item(span = { GridItemSpan(3) }) { Binder(cards, owned.keys, nav) }
        } else {
            items(cards, key = { it.key }) { c ->
                val n = owned[c.key] ?: 0
                CardTile(c, c.price, badge = if (n > 1) "x$n" else null, dim = n == 0) { nav.navigate(Routes.card(c.key)) }
            }
        }
    }
}

@Composable
fun KV(k: String, v: String) {
    Row { Text(k, color = Vault.text2, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(v, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** A 9-pocket binder: pages of 3 x 3 pockets, missing cards as ghosts. */
@Composable
private fun Binder(cards: List<Card>, owned: Set<String>, nav: NavHostController) {
    val pages = cards.chunked(9)
    if (pages.isEmpty()) return
    val state = rememberPagerState { pages.size }
    Column {
        HorizontalPager(state, Modifier.fillMaxWidth(), pageSpacing = 12.dp) { i ->
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Vault.surface).border(1.dp, Vault.line, RoundedCornerShape(16.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in pages[i].chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in row) {
                        val have = c.key in owned
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Vault.bg).padding(4.dp)) {
                            CardImage(c, Modifier.fillMaxWidth(), dim = !have)
                            if (!have) Text("#${c.number}", Modifier.align(Alignment.Center), color = Vault.text2, fontWeight = FontWeight.Bold)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f).aspectRatio(63f / 88f)) }
                }
            }
        }
        Text("Page ${state.currentPage + 1} of ${pages.size} · swipe to turn", color = Vault.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
