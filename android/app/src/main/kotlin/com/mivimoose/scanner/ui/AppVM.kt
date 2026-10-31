package com.mivimoose.scanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.scanner.Graph
import com.mivimoose.scanner.data.PackRow
import com.mivimoose.scanner.data.Portfolio
import com.mivimoose.scanner.data.PortfolioState
import com.mivimoose.scanner.data.ScanRow
import com.mivimoose.scanner.data.Settings
import com.mivimoose.scanner.data.Snapshot
import com.mivimoose.scanner.data.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App-wide state for the screens: catalog, collection valued at today's prices, history. */
class AppVM : ViewModel() {
    val catalog: StateFlow<Catalog?> = Graph.catalog.catalog
    val settings: StateFlow<Settings> = Graph.prefs.state
    val status = Graph.catalog.status
    val live = Graph.prices.live

    val portfolio: StateFlow<PortfolioState?> = combine(catalog, Graph.store.version, Graph.catalog.priceTick, Graph.money.rates) { c, _, _, _ ->
        c?.let { Portfolio.compute(it, Graph.store.owned()) { card, v -> Graph.prices.cachedGraded(card, v) } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val scans: StateFlow<List<ScanRow>> = Graph.store.version.map { Graph.store.scans(400) }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val snapshots: StateFlow<List<Snapshot>> = Graph.store.version.map { Graph.store.snapshots() }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val watches: StateFlow<List<Watch>> = Graph.store.version.map { Graph.store.watches() }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val packs: StateFlow<List<PackRow>> = Graph.store.version.map { Graph.store.packs() }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
