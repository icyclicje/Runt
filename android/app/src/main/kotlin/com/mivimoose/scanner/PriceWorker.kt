package com.mivimoose.scanner

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Background price refresh: the collection's prices, the daily value history and price alerts. */
class PriceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        repeat(40) { if (Graph.catalog.catalog.value == null) delay(500) }
        if (Graph.catalog.catalog.value == null) return Result.retry()
        return runCatching { Graph.prices.refreshCollection() }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PriceWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("prices", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
