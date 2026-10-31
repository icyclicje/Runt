package com.mivimoose.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.mivimoose.core.catalog.CardSet
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.catalog.LANGUAGE_FLAGS
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes

@Composable
fun CollectionScreen(vm: AppVM, nav: NavHostController) {
    val p by vm.portfolio.collectAsState()
    val cat by vm.catalog.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if ((p?.copies ?: 0) > 0) "sets" else "browse") }
    var lang by rememberSaveable { mutableStateOf("all") }
    var q by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("value") }
    val c = cat ?: return

    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = BottomBarSpace + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column {
                Text("Collection", style = MaterialTheme.typography.headlineMedium)
                p?.let { Text("${Graph.money.fmt(it.total)} · ${"%,d".format(it.copies)} cards · ${it.sets.size} sets", color = Vault.text2) }
                Spacer(Modifier.height(12.dp))
                Pills(listOf("sets" to "Set progress", "cards" to "My cards", "browse" to "All sets"), tab, { tab = it })
                Spacer(Modifier.height(10.dp))
                SearchField(q, { q = it }, if (tab == "cards") "Search your cards" else "Search sets")
                Spacer(Modifier.height(10.dp))
                val langs = listOf("all" to "All") + c.languages().map { it to (LANGUAGE_FLAGS[it] ?: it) }
                if (tab != "cards") Pills(langs, lang, { lang = it })
                if (tab == "cards") Pills(listOf("value" to "Value", "new" to "Newest", "name" to "Name", "set" to "Set"), sort, { sort = it })
            }
        }
        when (tab) {
            "sets" -> {
                val list = (p?.sets ?: emptyList()).filter { (lang == "all" || it.set.lang == lang) && match(it.set, q) }
                if (list.isEmpty()) item(span = { GridItemSpan(3) }) {
                    Empty(Icons.Default.AutoAwesomeMosaic, "No sets yet", "Scan or add cards and each set's progress shows up here, with what it's worth and what's missing.")
                }
                items(list, key = { "s" + it.set.id }, span = { GridItemSpan(3) }) { sp -> SetProgressRow(sp) { nav.navigate(Routes.set(sp.set.id)) } }
            }
            "cards" -> {
                val hs = (p?.holdings ?: emptyList()).groupBy { it.card.key }.map { (_, v) -> v.first() to v.sumOf { it.owned.qty } }
                    .filter { (h, _) -> q.isBlank() || Catalog.fold("${h.card.name} ${h.card.setName} ${h.card.number}").contains(Catalog.fold(q)) }
                    .let { l ->
                        when (sort) {
                            "new" -> l.sortedByDescending { it.first.owned.addedAt }
                            "name" -> l.sortedBy { it.first.card.name }
                            "set" -> l.sortedWith(compareBy({ it.first.card.setName }, { it.first.card.order }))
                            else -> l.sortedByDescending { it.first.unit }
                        }
                    }
                if (hs.isEmpty()) item(span = { GridItemSpan(3) }) {
                    Empty(Icons.Default.AutoAwesomeMosaic, "No cards yet", "Cards you scan or add appear here.")
                }
                items(hs, key = { "c" + it.first.card.key }) { (h, n) ->
                    CardTile(h.card, h.unit, badge = if (n > 1) "x$n" else null) { nav.navigate(Routes.card(h.card.key)) }
                }
            }
            else -> {
                val owned = p?.sets?.associateBy { it.set.id } ?: emptyMap()
                val sets = c.order.mapNotNull { c.set(it) }.filter { !it.firstEdition && (lang == "all" || it.lang == lang) && match(it, q) }
                var lastSeries = ""
                for (s in sets) {
                    if (s.series != lastSeries) {
                        lastSeries = s.series
                        item(span = { GridItemSpan(3) }, key = "h" + s.lang + s.series) {
                            Text(s.series.ifEmpty { "Other" }, style = Eyebrow, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    item(span = { GridItemSpan(3) }, key = "b" + s.id) { SetRow(s, owned[s.id]?.pct) { nav.navigate(Routes.set(s.id)) } }
                }
            }
        }
    }
}

private fun match(s: CardSet, q: String) = q.isBlank() || Catalog.fold("${s.name} ${s.nameLocal ?: ""} ${s.series} ${s.code ?: ""} ${s.id}").contains(Catalog.fold(q))

@Composable
fun SetRow(s: CardSet, pct: Float?, onClick: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 74.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                if (s.logo != null) AsyncImage(s.logo, s.name, Modifier.fillMaxSize())
                else Text(s.code ?: s.name.take(3).uppercase(), fontWeight = FontWeight.Black, color = Vault.text2)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (s.lang != "en") { Spacer(Modifier.width(6.dp)); Tag(LANGUAGE_FLAGS[s.lang] ?: s.lang, Vault.periwinkle) }
                    if (s.source == "tcgcsv" && s.release > java.time.LocalDate.now().minusDays(150).toString()) { Spacer(Modifier.width(6.dp)); Tag("NEW", Vault.mint) }
                }
                Text(listOfNotNull(s.release.take(4).ifEmpty { null }, s.code, "${s.cards.size} cards", s.nameLocal).joinToString(" · "), color = Vault.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pct != null) Ring(pct, 36.dp, 4.dp) { Text("${(pct * 100).toInt()}", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vault.surface2).border(1.dp, Vault.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Vault.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Vault.muted)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Vault.text, fontSize = 16.sp), cursorBrush = SolidColor(Vault.mint), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Text("Clear", color = Vault.mint, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onChange("") }.padding(4.dp))
    }
}
