package it.spese.app.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import it.spese.core.common.Dates
import it.spese.domain.finance.Period
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Filtri condivisi da Panoramica, Movimenti, Piano e Analisi (periodo, conto, categoria, tipo, testo, importo). */
@Singleton
class FilterStore @Inject constructor(settings: it.spese.domain.repository.SettingsRepository, @it.spese.data.di.ApplicationScope scope: kotlinx.coroutines.CoroutineScope) {
    /** Di partenza: il mese corrente (l'ultimo con movimenti), confrontato con il precedente. */
    private val _filter = MutableStateFlow(TxFilter(period = Period.CurrentMonth))
    /** Il filtro con il mese contabile scelto nelle impostazioni (calendario di default): i periodi lo seguono ovunque. */
    val filter: StateFlow<TxFilter> = kotlinx.coroutines.flow.combine(_filter, settings.observe().map { it.monthStartDay.coerceIn(1, 28) }.distinctUntilChanged()) { f, day -> f.copy(calendar = it.spese.core.common.MonthCalendar(day)) }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, _filter.value)
    fun update(transform: (TxFilter) -> TxFilter) { _filter.value = transform(_filter.value) }
    fun reset() { _filter.value = TxFilter(period = Period.CurrentMonth) }

    /** Richiesta di aprire la ricerca in Movimenti (dalla Home). */
    private val _searchRequested = MutableStateFlow(false)
    val searchRequested: StateFlow<Boolean> = _searchRequested.asStateFlow()
    fun requestSearch() { _searchRequested.value = true }
    fun consumeSearch() { _searchRequested.value = false }
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

    /** Schermata da aprire all'avvio (es. dal tocco su una notifica dell'app). */
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()
    fun offerRoute(r: String?) { _route.value = r }
    fun consumeRoute() { _route.value = null }
}
