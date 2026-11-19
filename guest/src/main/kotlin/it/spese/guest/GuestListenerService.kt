package it.spese.guest

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import it.spese.domain.capture.CaptureRouting
import it.spese.domain.sharing.GuestCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Legge le notifiche SOLO delle app scelte (banche, Wallet). Le altre si scartano alla prima riga, senza leggerne il testo.
 * Una spesa riconosciuta viene messa in coda e mandata, cifrata, a chi ha creato la condivisione.
 */
class GuestListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> android.util.Log.w("GuestListener", "notifica non elaborata", e) })

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try { requestRebind(android.content.ComponentName(this, GuestListenerService::class.java)) } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val app = application as GuestApp
        scope.launch {
            val st = app.store.get() ?: return@launch
            val source = CaptureRouting.route(GuestCapture.config(st.activeSources), sbn.packageName, title) ?: return@launch
            val text = listOfNotNull(
                (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ") { line -> line.toString() },
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            ).filter { s -> s.isNotBlank() }.distinct().joinToString(" · ")
            val ev = GuestCapture.eventFrom(source, sbn.packageName, title, text, sbn.postTime) ?: return@launch
            if (app.service.queue(listOf(ev)) > 0) GuestScheduler.syncSoon(applicationContext)
        }
    }
}
