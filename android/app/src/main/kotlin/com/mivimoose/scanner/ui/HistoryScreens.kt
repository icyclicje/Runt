package com.mivimoose.scanner.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.Routes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayHeader = DateTimeFormatter.ofPattern("EEEE, MMM d")

@Composable
fun RecentScreen(vm: AppVM, nav: NavHostController) {
    val scans by vm.scans.collectAsState()
    val cat by vm.catalog.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    val c = cat ?: return
    val list = scans.filter {
        when (filter) { "new" -> !it.duplicate && it.mode != "check"; "dupes" -> it.duplicate; "hits" -> it.price >= Graph.prefs.value.hitValue; "check" -> it.mode == "check"; else -> true }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BottomBarSpace + 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Recent scans", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (scans.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteOutline, "Clear history", tint = Vault.text2) }
            }
            Text("${scans.size} scans · ${Graph.money.fmt(scans.sumOf { it.price }, cents = false)} scanned in total", color = Vault.text2)
            Spacer(Modifier.height(10.dp))
            Pills(listOf("all" to "All", "new" to "New", "dupes" to "Duplicates", "hits" to "Hits", "check" to "Price checks"), filter, { filter = it })
            Spacer(Modifier.height(8.dp))
        }
        if (list.isEmpty()) item { Empty(Icons.Default.History, "No scans yet", "Everything you scan is listed here, newest first.") }
        var lastDay = ""
        for (s in list) {
            val day = Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault()).toLocalDate()
            if (day.toString() != lastDay) {
                lastDay = day.toString()
                val dayScans = list.filter { Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDate() == day }
                item(key = "d$lastDay") {
                    Row(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(day.format(dayHeader).uppercase(), style = Eyebrow, modifier = Modifier.weight(1f))
                        Text("${dayScans.size} · ${Graph.money.fmt(dayScans.sumOf { it.price }, cents = false)}", style = Eyebrow)
                    }
                }
            }
            val card = c.card(s.cardKey) ?: continue
            item(key = "s${s.id}") { ScanRowItem(card, s.variant, s.price, s.at, s.duplicate, s.mode) { nav.navigate(Routes.card(card.key)) } }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear scan history?") },
        text = { Text("Your collection stays as it is; only the list of scans is cleared.") },
        confirmButton = { TextButton(onClick = { Graph.store.clearScans(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
fun PacksScreen(vm: AppVM, nav: NavHostController) {
    val packs by vm.packs.collectAsState()
    val cat by vm.catalog.collectAsState()
    val c = cat ?: return
    val rows = remember(packs) { packs.map { it to Graph.store.scansOfPack(it.id) } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BottomBarSpace + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Packs", style = MaterialTheme.typography.titleLarge)
            }
            val value = rows.sumOf { r -> r.second.sumOf { it.price } }
            val cost = packs.sumOf { it.cost ?: 0.0 }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("PACKS", "${packs.size}", Modifier.weight(1f))
                StatTile("PULLED", Graph.money.fmt(value, cents = false), Modifier.weight(1f))
                StatTile("PAID", Graph.money.fmt(cost, cents = false), Modifier.weight(1f))
            }
            if (rows.size >= 2) {
                Spacer(Modifier.height(12.dp))
                Glass(Modifier.fillMaxWidth()) {
                    Text("Value of each pack", style = MaterialTheme.typography.titleSmall)
                    val shown = rows.take(24).reversed()
                    ColumnChart(shown.indices.map { "" }, shown.map { r -> r.second.sumOf { it.price } }, height = 120.dp,
                        fmtValue = { Graph.money.fmt(it) }, tooltipLabel = { i -> c.set(shown[i].first.setId)?.name ?: "" })
                    Text("Oldest to newest, last ${shown.size} packs", color = Vault.muted, fontSize = 11.sp)
                }
            }
        }
        if (packs.isEmpty()) item {
            Empty(Icons.Default.Inventory2, "No packs yet", "Turn on Track packs in Settings and scan each card as you open it. The set is detected by itself, and each pack's value is tracked against its price.")
        }
        items(rows, key = { it.first.id }) { (pk, scans) ->
            val set = c.set(pk.setId)
            val value = scans.sumOf { it.price }
            Glass(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(set?.name ?: pk.setId, fontWeight = FontWeight.SemiBold)
                        Text("${timeAgo(pk.openedAt)} · ${scans.size} cards" + if (!pk.done) " · open" else "", color = Vault.muted, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Graph.money.fmt(value), fontWeight = FontWeight.Bold)
                        pk.cost?.takeIf { it > 0 }?.let { Delta(value / it - 1, small = true) }
                    }
                }
                if (scans.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(scans.sortedByDescending { it.price }) { s ->
                            c.card(s.cardKey)?.let { card -> CardImage(card, Modifier.width(44.dp).tierGlow(tierOf(s.price), androidx.compose.foundation.shape.RoundedCornerShape(4.dp), small = true)) }
                        }
                    }
                }
                TextButton(onClick = { Graph.store.deletePack(pk.id) }) { Text("Delete pack", color = Vault.muted, fontSize = 12.sp) }
            }
        }
    }
}
