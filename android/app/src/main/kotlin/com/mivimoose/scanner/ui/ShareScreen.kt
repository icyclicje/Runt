package com.mivimoose.scanner.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.mivimoose.core.catalog.Card
import com.mivimoose.scanner.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private val STYLES = mapOf(
    "holo" to listOf(Color(0xFF0B0D16), Color(0xFF1A1036), Color(0xFF0A1F22)),
    "midnight" to listOf(Color(0xFF05060A), Color(0xFF0E1320), Color(0xFF05060A)),
    "gold" to listOf(Color(0xFF140E02), Color(0xFF2A1D05), Color(0xFF0B0802)),
)

@Composable
fun ShareScreen(vm: AppVM, nav: NavHostController, kind0: String, arg: String) {
    val ctx = LocalContext.current
    val p by vm.portfolio.collectAsState()
    val scans by vm.scans.collectAsState()
    val packs by vm.packs.collectAsState()
    val cat by vm.catalog.collectAsState()
    var kind by rememberSaveable { mutableStateOf(kind0) }
    var style by rememberSaveable { mutableStateOf(if (kind0 == "card") "gold" else "holo") }
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val c = cat ?: return

    fun capture(then: suspend (Bitmap) -> Unit) = scope.launch {
        val bmp = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        then(bmp)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Share picture", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (kind0 == "collection") Pills(listOf("collection" to "Collection", "top" to "Top pulls", "sets" to "Set progress", "pack" to "Last pack"), kind, { kind = it })
            Spacer(Modifier.height(8.dp))
            Pills(listOf("holo" to "Holo", "midnight" to "Midnight", "gold" to "Gold"), style, { style = it })
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp))
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) },
            ) {
                Poster(style) {
                    when (kind) {
                        "card" -> c.card(arg)?.let { CardPoster(it) }
                        "set" -> c.set(arg)?.let { s -> p?.sets?.firstOrNull { it.set.id == s.id }.let { sp -> SetPoster(s, sp) } }
                        "sets" -> SetsPoster(p?.sets ?: emptyList())
                        "top" -> {
                            val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
                            val top = scans.filter { it.at >= since }.sortedByDescending { it.price }.mapNotNull { s -> c.card(s.cardKey)?.let { it to s.price } }.distinctBy { it.first.key }.take(6)
                            GridPoster("TOP PULLS THIS WEEK", top, "${scans.count { it.at >= since }} cards scanned")
                        }
                        "pack" -> {
                            val pk = packs.firstOrNull()
                            val cards = pk?.let { Graph.store.scansOfPack(it.id).mapNotNull { s -> c.card(s.cardKey)?.let { it to s.price } } } ?: emptyList()
                            GridPoster(pk?.let { "PACK OF ${c.set(it.setId)?.name?.uppercase() ?: ""}" } ?: "NO PACKS YET", cards.sortedByDescending { it.second }.take(9),
                                "Pulled ${Graph.money.fmt(cards.sumOf { it.second })}" + (pk?.cost?.let { " · paid ${Graph.money.fmt(it)}" } ?: ""))
                        }
                        else -> {
                            val top = p?.holdings?.distinctBy { it.card.key }?.take(6)?.map { it.card to it.unit } ?: emptyList()
                            GridPoster("MY COLLECTION", top, "${Graph.money.fmt(p?.total ?: 0.0, cents = false)} · ${"%,d".format(p?.copies ?: 0)} cards · ${p?.sets?.size ?: 0} sets")
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { capture { shareBitmap(ctx, it) } }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Vault.mint, contentColor = Vault.bg)) {
                    Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(6.dp)); Text("Share", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { capture { saveBitmap(ctx, it) } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Save")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Poster(style: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(STYLES[style] ?: STYLES.getValue("holo")))) {
        if (style == "holo") HoloSheen(Modifier.fillMaxSize(), alpha = 0.08f, shape = RoundedCornerShape(0.dp))
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoloText("MIVIMOOSE RIPS", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp))
                Spacer(Modifier.weight(1f))
                Text("TCGplayer prices · ${LocalDate.now()}", color = Vault.muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun GridPoster(title: String, cards: List<Pair<Card, Double>>, sub: String) {
    Column {
        HoloText(title, TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp))
        Text(sub, color = Vault.text2, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        val cols = if (cards.size > 6) 3 else if (cards.size > 2) 3 else 2
        for (row in cards.chunked(cols)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                for ((card, price) in row) Column(Modifier.weight(1f)) {
                    val tier = tierOf(price)
                    CardImage(card, Modifier.fillMaxWidth().tierGlow(tier, RoundedCornerShape(6.dp)))
                    Text(Graph.money.fmt(price, cents = price < 100), color = if (tier > 0) Vault.tierColor(tier) else Vault.text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(card.name, color = Vault.text2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (cards.isEmpty()) Text("Nothing here yet: scan some cards first.", color = Vault.muted)
    }
}

@Composable
private fun CardPoster(card: Card) {
    val price = card.price
    val tier = tierOf(price)
    val g = Graph.prices.cachedGraded(card, card.variants().first().id)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (tier > 0) HoloText(Vault.tierName(tier), TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(190.dp)) {
            CardImage(card, Modifier.fillMaxWidth().tierGlow(tier.coerceAtLeast(1), RoundedCornerShape(10.dp)), large = true)
            HoloSheen(Modifier.matchParentSize(), alpha = 0.18f, shape = RoundedCornerShape(10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(card.name, style = MaterialTheme.typography.titleLarge)
        Text("${card.setName} · #${card.number}", color = Vault.text2, fontSize = 12.sp)
        Text(Graph.money.fmt(price), style = MaterialTheme.typography.headlineMedium, color = if (tier > 0) Vault.tierColor(tier) else Vault.text)
        g?.let { Text("PSA 10 ${Graph.money.fmt(it.psa10)} · pop ${"%,d".format(it.psaTotal)}", color = Vault.text2, fontSize = 12.sp) }
    }
}

@Composable
private fun SetPoster(s: com.mivimoose.core.catalog.CardSet, sp: com.mivimoose.scanner.data.SetProgress?) {
    val owned = Graph.store.owned().map { it.cardKey }.toSet()
    Column {
        if (s.logo != null) AsyncImage(s.logo, s.name, Modifier.height(48.dp))
        Text(s.name.uppercase(), fontWeight = FontWeight.Black, fontSize = 18.sp, color = Vault.text)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
            Ring(sp?.pct ?: 0f, 96.dp, 9.dp) { Text("${((sp?.pct ?: 0f) * 100).toInt()}%", fontSize = 22.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("${sp?.ownedMain ?: 0} of ${s.cards.count { it.mainSet }} cards", fontWeight = FontWeight.Bold, color = Vault.text)
                Text("Master set ${sp?.ownedAll ?: 0}/${s.cards.size}", color = Vault.text2, fontSize = 12.sp)
                Text("Worth ${Graph.money.fmt(sp?.value ?: 0.0, cents = false)}", color = Vault.text2, fontSize = 12.sp)
            }
        }
        // the whole set as tiles: owned bright, missing dark
        val cols = 15
        for (row in s.sortedCards.chunked(cols)) Row(Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (c in row) Box(Modifier.weight(1f).aspectRatio(0.72f).clip(RoundedCornerShape(2.dp)).background(if (c.key in owned) Vault.mint else Vault.surface3))
            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SetsPoster(sets: List<com.mivimoose.scanner.data.SetProgress>) {
    Column {
        HoloText("SET PROGRESS", TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp))
        Spacer(Modifier.height(10.dp))
        for (sp in sets.take(7)) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
            Ring(sp.pct, 38.dp, 4.dp) { Text("${(sp.pct * 100).toInt()}", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(sp.set.name, fontWeight = FontWeight.SemiBold, maxLines = 1, color = Vault.text)
                Text("${sp.ownedMain}/${sp.totalMain} · ${Graph.money.fmt(sp.value, cents = false)}", color = Vault.text2, fontSize = 11.sp)
            }
        }
        if (sets.isEmpty()) Text("Scan cards to start your sets.", color = Vault.muted)
    }
}

private suspend fun shareBitmap(ctx: Context, bmp: Bitmap) {
    val f = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        File(dir, "mivimoose-${System.currentTimeMillis()}.png").also { file -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri("", uri)     // lets the share sheet preview the picture
    ctx.startActivity(Intent.createChooser(send, "Share picture"))
    Graph.sounds.play("select")
}

private suspend fun saveBitmap(ctx: Context, bmp: Bitmap) {
    val ok = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mivimoose-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MivimooseRips")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } != null
    }
    Toast.makeText(ctx, if (ok) "Saved to Pictures/MivimooseRips" else "Couldn't save (use Share instead)", Toast.LENGTH_SHORT).show()
    if (ok) Graph.sounds.play("select")
}
