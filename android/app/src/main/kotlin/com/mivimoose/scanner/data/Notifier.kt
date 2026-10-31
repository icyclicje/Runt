package com.mivimoose.scanner.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mivimoose.core.catalog.Card
import com.mivimoose.scanner.MainActivity
import com.mivimoose.scanner.R

/** Price-alert notifications for watched cards. */
object Notifier {
    private const val CHANNEL = "price_alerts"
    private lateinit var app: Context
    lateinit var money: Money

    fun init(ctx: Context, m: Money) {
        app = ctx.applicationContext
        money = m
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Price alerts", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "When a card on your watchlist reaches your target price"
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun priceAlert(card: Card, w: Watch, price: Double) {
        if (!::app.isInitialized) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(app, card.key.hashCode(),
            Intent(app, MainActivity::class.java).putExtra("card", card.key).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dir = if (w.above) "rose to" else "dropped to"
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("${card.name} $dir ${money.fmt(price)}")
            .setContentText("${card.setName} #${card.number} · your target ${money.fmt(w.target)}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(card.key.hashCode(), n) }
    }
}
