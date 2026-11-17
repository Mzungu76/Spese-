package it.spese.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import it.spese.domain.repository.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Pulsante "Annulla" dell'avviso: elimina il movimento provvisorio appena creato (solo se è ancora un movimento da notifica). */
@AndroidEntryPoint
class CaptureUndoReceiver : BroadcastReceiver() {
    @Inject lateinit var tx: TransactionRepository
    @Inject lateinit var notifier: CaptureNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(CaptureNotifier.EXTRA_TX, 0L).takeIf { it > 0 } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tx.get(id)?.takeIf { t -> t.origin == it.spese.core.model.TxOrigin.NOTIFICATION }?.let { tx.delete(listOf(id)) }
                notifier.cancel(id)
            } finally { pending.finish() }
        }
    }
}
