package com.mivimoose.scanner.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.mivimoose.scanner.BottomBarSpace
import com.mivimoose.scanner.BuildConfig
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.data.Money
import com.mivimoose.scanner.data.Portfolio
import com.mivimoose.scanner.data.Settings
import com.mivimoose.scanner.data.Sounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(vm: AppVM, nav: NavHostController) {
    val s by vm.settings.collectAsState()
    val status by vm.status.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    fun set(f: (Settings) -> Settings) = Graph.prefs.update(f)

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = BottomBarSpace + 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Section("Prices") {
            Text("Currency", color = Vault.text2, fontSize = 13.sp)
            Pills(Money.SYMBOLS.keys.map { it to it }, s.currency, { c -> set { it.copy(currency = c) }; Graph.money.currency = c })
            Spacer(Modifier.height(10.dp))
            Text("Live price refresh while the app is open: every ${s.refreshMinutes} min", color = Vault.text2, fontSize = 13.sp)
            Slider(s.refreshMinutes.toFloat(), { v -> set { it.copy(refreshMinutes = v.toInt()) } }, valueRange = 5f..60f, steps = 10)
            Text("Value tiers (USD)", color = Vault.text2, fontSize = 13.sp)
            Row {
                MoneyField("Hit", s.hitValue, Modifier.weight(1f)) { v -> set { it.copy(hitValue = v) } }
                Spacer(Modifier.padding(4.dp))
                MoneyField("Big hit", s.bigValue, Modifier.weight(1f)) { v -> set { it.copy(bigValue = v) } }
                Spacer(Modifier.padding(4.dp))
                MoneyField("Jackpot", s.jackpotValue, Modifier.weight(1f)) { v -> set { it.copy(jackpotValue = v) } }
            }
            OutlinedButton(onClick = { scope.launch { Graph.prices.refreshCollection(force = true) } }, modifier = Modifier.padding(top = 8.dp)) { Text("Update all prices now") }
        }
        Section("Sound & haptics") {
            Toggle("Sounds", s.sounds) { v -> set { it.copy(sounds = v) } }
            Toggle("Button sounds", s.uiSounds) { v -> set { it.copy(uiSounds = v) } }
            Toggle("Vibration", s.haptics) { v -> set { it.copy(haptics = v) } }
            Text("Volume", color = Vault.text2, fontSize = 13.sp)
            Slider(s.volume, { v -> set { it.copy(volume = v) } }, valueRange = 0.1f..1f)
            Text("Jackpot sound (cards over ${Graph.money.fmt(s.jackpotValue, cents = false)})", color = Vault.text2, fontSize = 13.sp)
            Pills(Sounds.JACKPOT_STYLES.map { it to it.replaceFirstChar(Char::uppercase) }, s.jackpotSound, { v -> set { it.copy(jackpotSound = v) }; Graph.sounds.play("jackpot_$v") })
            Row(Modifier.padding(top = 8.dp)) {
                for (k in listOf("new", "dupe", "hit", "big")) {
                    TextButton(onClick = { Graph.sounds.play(k) }) { Icon(Icons.Default.PlayArrow, null); Text(k.replaceFirstChar(Char::uppercase)) }
                }
            }
        }
        Section("Scanning") {
            Text("When you scan a card you already own", color = Vault.text2, fontSize = 13.sp)
            Pills(listOf("warn" to "Add it, warn me", "skip" to "Don't add", "ask" to "Ask"), s.dupePolicy, { v -> set { it.copy(dupePolicy = v) } })
            Spacer(Modifier.height(8.dp))
            Text("Condition of scanned cards", color = Vault.text2, fontSize = 13.sp)
            Pills(listOf("NM" to "Near mint", "LP" to "Lightly played", "MP" to "Moderately played", "HP" to "Heavily played", "DMG" to "Damaged"), s.defaultCondition, { v -> set { it.copy(defaultCondition = v) } })
            Toggle("Read card text (number, set code, name)", s.ocr) { v -> set { it.copy(ocr = v) } }
            Text("Reading the text lets the scanner identify cards from any set without loading it first. Everything stays on the phone.", color = Vault.muted, fontSize = 12.sp)
        }
        Section("Graded cards") {
            Toggle("Show PSA population and graded prices", s.showGraded) { v -> set { it.copy(showGraded = v) } }
            Text("Population and graded prices come from PriceCharting (personal use), cached for a week per card.", color = Vault.muted, fontSize = 12.sp)
        }
        Section("Display") {
            Toggle("Reduce motion (no tilt, confetti or shake)", s.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }
        }
        Section("Data") {
            Text(status, color = Vault.text2, fontSize = 13.sp)
            Text("Card images for recognition: ${"%.1f".format(Graph.refs.diskBytes() / 1e6)} MB", color = Vault.text2, fontSize = 13.sp)
            Row {
                OutlinedButton(onClick = { Graph.catalog.refresh(force = true) }) { Text("Update card data") }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { Graph.refs.clear() }) { Text("Clear images") }
            }
            Row(Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { scope.launch { exportCsv(ctx) } }) { Text("Export collection (CSV)") }
                Spacer(Modifier.padding(4.dp))
                TextButton(onClick = { confirmReset = true }) { Text("Reset everything", color = Vault.badText) }
            }
        }
        Section("About") {
            Text("MivimooseRips ${BuildConfig.VERSION_NAME}", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(
                "Card data: the MivimooseRips site, TCGplayer's catalog (via tcgcsv.com) and TCGdex. Prices: TCGplayer market prices. " +
                    "Graded data: PriceCharting. Pull rates: TCGplayer and community opening studies (sources shown per set). " +
                    "Pokémon and its trademarks belong to Nintendo, Creatures and GAME FREAK; this app is unofficial and for personal use.",
                color = Vault.muted, fontSize = 12.sp,
            )
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset everything?") },
        text = { Text("Deletes your collection, scans, packs, value history and alerts. This can't be undone.") },
        confirmButton = { TextButton(onClick = { Graph.store.wipeAll(); confirmReset = false }) { Text("Delete all", color = Vault.badText) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionTitle(title, Modifier.padding(top = 12.dp))
    Glass(Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Vault.mint, checkedThumbColor = Vault.bg))
    }
}

@Composable
private fun MoneyField(label: String, v: Double, modifier: Modifier, onChange: (Double) -> Unit) {
    var text by remember(v) { mutableStateOf(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) }
    OutlinedTextField(text, { t -> text = t; t.toDoubleOrNull()?.let(onChange) }, modifier, label = { Text(label) }, singleLine = true)
}

private suspend fun exportCsv(ctx: Context) {
    val c = Graph.catalog.catalog.value ?: return
    val f = withContext(Dispatchers.IO) {
        val p = Portfolio.compute(c, Graph.store.owned())
        val sb = StringBuilder("set,number,name,rarity,language,printing,condition,grade,quantity,price_usd,value_usd,tcgplayer_id\n")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        for (h in p.holdings) {
            val o = h.owned; val card = h.card
            sb.append(listOf(q(card.setName), q(card.number), q(card.name), q(card.rarity), card.lang, q(card.variantLabel(o.variant)), o.condition, o.grade ?: "",
                o.qty, "%.2f".format(h.unit), "%.2f".format(h.value), card.tcgId ?: "").joinToString(",")).append('\n')
        }
        File(File(ctx.cacheDir, "share").apply { mkdirs() }, "mivimoose-collection.csv").also { it.writeText(sb.toString()) }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri("", uri)
    ctx.startActivity(Intent.createChooser(send, "Export collection"))
}
