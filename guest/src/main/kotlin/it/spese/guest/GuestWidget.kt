package it.spese.guest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.spese.domain.sharing.GuestAlert
import it.spese.domain.sharing.GuestSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Widget per la schermata Home: solo la cifra al giorno, presa dall'ultimo riepilogo ricevuto. */
class GuestWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { render(context, manager, ids) } finally { pending.finish() } }
    }

    companion object {
        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val st = (context.applicationContext as GuestApp).store.get()
            val views = RemoteViews(context.packageName, R.layout.widget_perday)
            views.setTextViewText(R.id.widget_value, GuestSummary.perDay(st?.snapshot))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        /** Da chiamare dopo ogni aggiornamento del riepilogo. */
        suspend fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, GuestWidget::class.java))
            if (ids.isNotEmpty()) render(context, mgr, ids)
        }
    }
}

/** Notifiche locali dell'app (limite all'80%, al 100%, riepilogo del lunedì). */
object GuestNotifier {
    private const val CHANNEL = "avvisi"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun post(context: Context, alert: GuestAlert) {
        if (!canPost(context)) return
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Avvisi", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(alert.title).setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text)).setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(alert.key.hashCode(), n) } catch (_: SecurityException) { }
    }
}
