package it.spese.app.capture

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import it.spese.domain.capture.*
import it.spese.domain.repository.CaptureConfigRepository
import it.spese.domain.usecase.CaptureNotificationUseCase
import it.spese.domain.usecase.CaptureResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Legge le notifiche SOLO delle fonti scelte dall'utente. Tutte le altre vengono scartate alla prima riga, senza leggerne il testo:
 * nessun contenuto viene conservato, e l'app non ha comunque alcun accesso alla rete.
 */
@AndroidEntryPoint
class CaptureListenerService : NotificationListenerService() {
    @Inject lateinit var config: CaptureConfigRepository
    @Inject lateinit var capture: CaptureNotificationUseCase
    @Inject lateinit var learning: CaptureLearning
    @Inject lateinit var notifier: CaptureNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var current = CaptureConfig()
    @Volatile private var loaded = false

    override fun onCreate() {
        super.onCreate()
        scope.launch { config.observe().collect { current = it; loaded = true } }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        // modalità "Rileva app": solo il nome dell'app, mai il testo
        if (learning.active) {
            @Suppress("DEPRECATION")
            val info = extras.getParcelable<ApplicationInfo>("android.appInfo")
            val label = runCatching { info?.loadLabel(packageManager)?.toString() }.getOrNull() ?: sbn.packageName
            learning.note(sbn.packageName, label)
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        scope.launch {
            val cfg = if (loaded) current else config.get()
            val source = CaptureRouting.route(cfg, sbn.packageName, title) ?: return@launch
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            // notifiche che l'utente ha chiesto di ignorare (mai se contengono un importo)
            if (IgnoreFilter.matches(cfg.ignoreRules, source.id, title, text)) return@launch
            val raw = RawNotification(sbn.packageName, title, text, sbn.postTime)
            when (val r = capture(source, cfg.of(source).accountId, raw)) {
                is CaptureResult.Added -> if (cfg.notifyOnAdd) notifier.added(r.txId, r.description, r.amount)
                CaptureResult.Unrecognized -> config.update { c -> c.copy(unrecognized = listOf(UnrecognizedNotification(System.currentTimeMillis(), source.label, "$title — $text".trim())) + c.unrecognized) }
                CaptureResult.Duplicate -> Unit
            }
        }
    }
}
