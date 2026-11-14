package it.spese.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import it.spese.app.MainActivity
import it.spese.core.common.Money
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** Avviso discreto "Aggiunto: COOP −12,50 €" con il pulsante Annulla (elimina il movimento appena creato). */
@Singleton
class CaptureNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    fun added(txId: Long, description: String, amount: BigDecimal) {
        ensureChannel()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val undo = PendingIntent.getBroadcast(context, txId.toInt(), Intent(context, CaptureUndoReceiver::class.java).putExtra(EXTRA_TX, txId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aggiunto: $description").setContentText(Money.eurSigned(amount)).setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).setTimeoutAfter(60_000)
            .addAction(0, "Annulla", undo).build()
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + txId.toInt(), n) } catch (_: SecurityException) { }
    }

    fun cancel(txId: Long) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_BASE + txId.toInt())

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Movimenti da notifiche", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL = "capture"
        const val EXTRA_TX = "tx_id"
        private const val NOTIFICATION_BASE = 50_000
    }
}
