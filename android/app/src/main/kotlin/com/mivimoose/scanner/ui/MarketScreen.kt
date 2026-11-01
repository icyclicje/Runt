package com.mivimoose.scanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.LANGUAGE_FLAGS
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes

@Composable
fun MarketScreen(vm: AppVM, nav: NavHostController) {
    val cat by vm.catalog.collectAsState()
    val watches by vm.watches.collectAsState()
    Graph.catalog.priceTick.collectAsState().value
    var q by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf("all") }
    val c = cat ?: return
    val results = rememberSearch(q, lang.takeIf { it != "all" }, limit = 80)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = BottomBarSpace + 16.dp)) {
        item {
            Text("Market", style = MaterialTheme.typography.headlineMedium)
            Text("TCGplayer prices for ${"%,d".format(c.cardCount)} cards in ${c.languages().size} languages", color = Vault.text2)
            Spacer(Modifier.height(12.dp))
            SearchField(q, { q = it }, "Search any card: name, number, set")
            Spacer(Modifier.height(8.dp))
            Pills(listOf("all" to "All") + c.languages().map { it to (LANGUAGE_FLAGS[it] ?: it) }, lang, { lang = it })
            Spacer(Modifier.height(8.dp))
        }
        if (q.length >= 2) {
            if (results.isEmpty()) item { Text("No cards match.", color = Vault.muted, modifier = Modifier.padding(16.dp)) }
            items(results, key = { it.key }) { card -> CardRow(card) { nav.navigate(Routes.card(card.key)) } }
            return@LazyColumn
        }
        if (watches.isNotEmpty()) item {
            SectionTitle("Watchlist")
            Glass(padding = PaddingValues(6.dp)) {
                for (w in watches) {
                    val card = c.card(w.cardKey) ?: continue
                    val now = card.priceFor(w.variant)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { nav.navigate(Routes.card(card.key)) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardImage(card, Modifier.width(36.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(card.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.NotificationsActive, null, tint = Vault.mint, modifier = Modifier.padding(end = 4.dp).height(14.dp))
                                Text((if (w.above) "above " else "below ") + Graph.money.fmt(w.target), color = Vault.muted, fontSize = 12.sp)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Graph.money.fmt(now), fontWeight = FontWeight.Bold)
                            if (w.target > 0) Text("%+.0f%% to target".format((w.target - now) / now.coerceAtLeast(0.01) * 100), color = Vault.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        val newest = c.order.mapNotNull { c.set(it) }.filter { !it.firstEdition && it.cards.size >= 40 && !it.id.startsWith("tcg-") }
        val newEn = newest.filter { it.lang == "en" }.sortedByDescending { it.release }.take(4)
        val newJa = newest.filter { it.lang == "ja" }.sortedByDescending { it.release }.take(2)
        for (s in newEn + newJa) item(key = "chase" + s.id) {
            SectionTitle("Chase cards · ${s.name}" + if (s.lang != "en") " (${LANGUAGE_FLAGS[s.lang]})" else "", Modifier.padding(top = 10.dp), "Set") { nav.navigate(Routes.set(s.id)) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(s.cards.sortedByDescending { it.price }.take(10), key = { it.key }) { card ->
                    CardTile(card, card.price, Modifier.width(110.dp)) { nav.navigate(Routes.card(card.key)) }
                }
            }
        }
        item {
            SectionTitle("Pack value right now", Modifier.padding(top = 14.dp))
            Text("Average value of one pack against its market price, from pull rates and today's prices.", color = Vault.muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        val packs = Graph.catalog.packs
        val ranked = newest.filter { it.lang == "en" && it.sealedPrice != null }.sortedByDescending { it.release }.take(16)
            .map { s -> Triple(s, packs.expectedValue(s), s.sealedPrice!!) }.sortedByDescending { it.second / it.third }
        item { LaunchedEffect(Unit) { for (s in ranked) Graph.prices.refreshPack(s.first) } }
        items(ranked, key = { "ev" + it.first.id }) { (s, ev, price) ->
            Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { nav.navigate(Routes.tool("ev") + "?set=" + android.net.Uri.encode(s.id)) }, padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        Text("Pack ${Graph.money.fmt(price)} · avg pull ${Graph.money.fmt(ev)}", color = Vault.text2, fontSize = 12.sp)
                    }
                    Delta(ev / price - 1)
                }
            }
        }
    }
}

@Composable
fun CardRow(card: Card, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(card, Modifier.width(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.nameLocal?.let { Text(it, color = Vault.text2, fontSize = 12.sp, maxLines = 1) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${card.setName} · #${card.number}", color = Vault.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (card.lang != "en") { Spacer(Modifier.width(6.dp)); Tag(LANGUAGE_FLAGS[card.lang] ?: card.lang, Vault.periwinkle) }
            }
        }
        val tier = tierOf(card.price)
        Text(Graph.money.fmt(card.price), fontWeight = FontWeight.Bold, color = if (tier > 0) Vault.tierColor(tier) else Vault.text)
    }
}
