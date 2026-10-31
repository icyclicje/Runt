package com.mivimoose.scanner

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mivimoose.scanner.ui.AppVM
import com.mivimoose.scanner.ui.CardScreen
import com.mivimoose.scanner.ui.CollectionScreen
import com.mivimoose.scanner.ui.HomeScreen
import com.mivimoose.scanner.ui.MarketScreen
import com.mivimoose.scanner.ui.PacksScreen
import com.mivimoose.scanner.ui.RecentScreen
import com.mivimoose.scanner.ui.ScanScreen
import com.mivimoose.scanner.ui.SetScreen
import com.mivimoose.scanner.ui.SettingsScreen
import com.mivimoose.scanner.ui.ShareScreen
import com.mivimoose.scanner.ui.ToolScreen
import com.mivimoose.scanner.ui.ToolsScreen
import com.mivimoose.scanner.ui.Vault
import com.mivimoose.scanner.ui.VaultTheme

/** Routes. Card keys and set ids are URL-encoded. */
object Routes {
    fun card(key: String) = "card/" + Uri.encode(key)
    fun set(id: String) = "set/" + Uri.encode(id)
    fun tool(id: String) = "tool/$id"
    fun share(kind: String = "collection", arg: String = "") = "share/$kind?arg=" + Uri.encode(arg)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val startCard = intent.getStringExtra("card")
        val testFrames = intent.getStringExtra("frames")
        val startRoute = intent.getStringExtra("route")
        setContent {
            VaultTheme {
                val nav = rememberNavController()
                val vm: AppVM = viewModel()
                LiveWhileVisible()
                Box(Modifier.fillMaxSize().background(Vault.bg)) {
                    NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                        composable("home") { HomeScreen(vm, nav) }
                        composable("collection") { CollectionScreen(vm, nav) }
                        composable("scan") { ScanScreen(vm, nav, testFrames) }
                        composable("market") { MarketScreen(vm, nav) }
                        composable("tools") { ToolsScreen(nav) }
                        composable("card/{key}") { CardScreen(vm, nav, Uri.decode(it.arguments?.getString("key") ?: "")) }
                        composable("set/{id}") { SetScreen(vm, nav, Uri.decode(it.arguments?.getString("id") ?: "")) }
                        composable("tool/{id}?set={set}") { ToolScreen(vm, nav, it.arguments?.getString("id") ?: "", it.arguments?.getString("set")?.let(Uri::decode)) }
                        composable("share/{kind}?arg={arg}") {
                            ShareScreen(vm, nav, it.arguments?.getString("kind") ?: "collection", Uri.decode(it.arguments?.getString("arg") ?: ""))
                        }
                        composable("recent") { RecentScreen(vm, nav) }
                        composable("packs") { PacksScreen(vm, nav) }
                        composable("settings") { SettingsScreen(vm, nav) }
                    }
                    BottomBar(nav, Modifier.align(Alignment.BottomCenter))
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    when {
                        startCard != null -> nav.navigate(Routes.card(startCard))
                        testFrames != null -> nav.navigate("scan")
                        startRoute != null -> nav.navigate(startRoute)
                    }
                }
            }
        }
    }
}

/** Live price refresh only while the app is on screen. */
@Composable
private fun LiveWhileVisible() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> Graph.prices.startLive()
                Lifecycle.Event.ON_STOP -> Graph.prices.stopLive()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

private val TABS = listOf(
    Triple("home", "Home", Icons.Default.SpaceDashboard),
    Triple("collection", "Collection", Icons.Default.AutoAwesomeMosaic),
    Triple("scan", "Scan", Icons.Default.DocumentScanner),
    Triple("market", "Market", Icons.Default.ShowChart),
    Triple("tools", "Tools", Icons.Default.Construction),
)

@Composable
private fun BottomBar(nav: NavHostController, modifier: Modifier) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    if (route == "scan" || route.startsWith("share")) return
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Vault.bg.copy(alpha = 0f), Vault.bg.copy(alpha = 0.96f), Vault.bg)))
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.Bottom,
    ) {
        for ((r, label, icon) in TABS) {
            val on = route == r
            if (r == "scan") ScanButton { go(nav, r) } else TabItem(label, icon, on) { go(nav, r) }
        }
    }
}

private fun go(nav: NavHostController, r: String) {
    Graph.sounds.play("click")
    nav.navigate(r) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (on) Vault.text else Vault.muted, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.sp, color = if (on) Vault.text else Vault.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ScanButton(onClick: () -> Unit) {
    Box(
        Modifier.offset(y = (-6).dp).size(62.dp)
            .shadow(18.dp, CircleShape, ambientColor = Vault.mint, spotColor = Vault.mint)
            .clip(CircleShape).background(Vault.holoBrush)
            .border(2.dp, Vault.bg, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.DocumentScanner, "Scan", tint = Vault.bg, modifier = Modifier.size(30.dp))
    }
}

/** Space the bottom bar takes over screens. */
val BottomBarSpace = 96.dp
