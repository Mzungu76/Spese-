package it.spese.app.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import it.spese.app.common.UiMessenger
import it.spese.domain.capture.BankNotificationParser
import it.spese.domain.repository.SettingsRepository
import it.spese.domain.usecase.CaptureNotificationUseCase
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Ultime app che hanno mandato notifiche di pagamento riconoscibili (solo in memoria): servono a proporre quali abilitare. */
@Singleton
class CaptureLog @Inject constructor() {
    private val _seen = MutableStateFlow<List<String>>(emptyList())
    val seen: StateFlow<List<String>> = _seen.asStateFlow()
    fun saw(pkg: String) { if (pkg !in _seen.value) _seen.value = (listOf(pkg) + _seen.value).take(8) }
}

/**
 * Trasforma le notifiche di pagamento delle app abilitate in movimenti. Tutto avviene sul dispositivo:
 * l'app non ha accesso alla rete e non legge le notifiche delle app non selezionate.
 */
@AndroidEntryPoint
class BankNotificationListener : NotificationListenerService() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var capture: CaptureNotificationUseCase
    @Inject lateinit var log: CaptureLog
    @Inject lateinit var messenger: UiMessenger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName ?: return
        if (pkg == packageName) return
        val notification = n.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        scope.launch {
            try {
                val s = settings.get()
                if (!s.captureEnabled) return@launch
                if (BankNotificationParser.parse(title, text) == null) return@launch
                log.saw(pkg)
                val allowed = s.capturePackages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                if (pkg !in allowed) return@launch
                if (capture(pkg, title, text, LocalDate.now())) messenger.show("Movimento aggiunto dalla notifica")
            } catch (_: Exception) { /* una notifica non leggibile non deve mai fermare il servizio */ }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
