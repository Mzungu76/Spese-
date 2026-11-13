package it.spese.app.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import it.spese.core.common.Dates
import it.spese.domain.finance.TxFilter
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Filtri condivisi da Panoramica, Movimenti, Piano e Analisi (periodo, conto, categoria, tipo, testo, importo). */
@Singleton
class FilterStore @Inject constructor() {
    private val _filter = MutableStateFlow(TxFilter())
    val filter: StateFlow<TxFilter> = _filter.asStateFlow()
    fun update(transform: (TxFilter) -> TxFilter) { _filter.value = transform(_filter.value) }
    fun reset() { _filter.value = TxFilter() }
}

/** Messaggi brevi (snackbar) emessi dai ViewModel. */
@Singleton
class UiMessenger @Inject constructor() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun show(text: String) { _messages.tryEmit(text) }
}

fun LocalDate.dayLabel(today: LocalDate): String = when (this) {
    today -> "Oggi"
    today.minusDays(1) -> "Ieri"
    else -> Dates.MESI_LUNGHI[monthValue - 1].let { m ->
        val dow = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")[dayOfWeek.value - 1]
        "$dow $dayOfMonth $m" + if (year != today.year) " $year" else ""
    }
}

object Share {
    /** Scrive i byte in cache e lancia lo share sheet di Android (nessun invio automatico verso servizi). */
    fun shareBytes(context: Context, name: String, mime: String, bytes: ByteArray, title: String = "Condividi") {
        val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val f = File(dir, name).also { it.writeBytes(bytes) }
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun stamp(): String = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))

/** File (backup JSON) ricevuto da un'altra app tramite "Apri con": viene proposto per il ripristino con anteprima e conferma. */
@Singleton
class IncomingFile @Inject constructor() {
    private val _uri = MutableStateFlow<Uri?>(null)
    val uri: StateFlow<Uri?> = _uri.asStateFlow()
    fun offer(u: Uri?) { _uri.value = u }
    fun consume() { _uri.value = null }
}
