package it.spese.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.capture.CaptureLearning
import it.spese.domain.capture.*
import it.spese.domain.repository.AppData
import it.spese.domain.repository.CaptureConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repo: CaptureConfigRepository, val data: StateFlow<AppData>, private val learning: CaptureLearning,
    private val completePending: it.spese.domain.usecase.CompletePendingAmountUseCase, private val messenger: it.spese.app.common.UiMessenger,
) : ViewModel() {
    val config: StateFlow<CaptureConfig> = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CaptureConfig())
    val seen: StateFlow<List<CaptureLearning.Seen>> = learning.seen
    val learningUntil: StateFlow<Long> = learning.until

    fun updateSource(s: CaptureSource, t: (SourceConfig) -> SourceConfig) { viewModelScope.launch { repo.update { c -> c.copy(sources = c.sources + (s.id to t(c.of(s)))) } } }
    fun setNotify(on: Boolean) { viewModelScope.launch { repo.update { it.copy(notifyOnAdd = on) } } }
    fun clearUnrecognized() { viewModelScope.launch { repo.update { it.copy(unrecognized = emptyList()) } } }
    fun removeUnrecognized(atMs: Long) { viewModelScope.launch { repo.update { c -> c.copy(unrecognized = c.unrecognized.filter { it.atMs != atMs }) } } }
    /** Quante notifiche già in elenco la regola scarterebbe. */
    fun wouldRemove(rule: IgnoreRule): Int = config.value.unrecognized.count { IgnoreFilter.matches(listOf(rule), IgnoreFilter.sourceIdOf(it.source), "", it.text) }
    fun addIgnoreRule(rule: IgnoreRule) { viewModelScope.launch { repo.update { c ->
        c.copy(ignoreRules = c.ignoreRules + rule, unrecognized = c.unrecognized.filterNot { IgnoreFilter.matches(listOf(rule), IgnoreFilter.sourceIdOf(it.source), "", it.text) })
    } } }
    fun toggleIgnoreRule(createdMs: Long, on: Boolean) { viewModelScope.launch { repo.update { c -> c.copy(ignoreRules = c.ignoreRules.map { if (it.createdMs == createdMs) it.copy(enabled = on) else it }) } } }
    fun removeIgnoreRule(createdMs: Long) { viewModelScope.launch { repo.update { c -> c.copy(ignoreRules = c.ignoreRules.filter { it.createdMs != createdMs }) } } }
    fun removeUnrecognized(items: List<UnrecognizedNotification>) { val ids = items.map { it.atMs }.toSet(); viewModelScope.launch { repo.update { c -> c.copy(unrecognized = c.unrecognized.filter { it.atMs !in ids }) } } }
    /** Completa un movimento senza importo: nasce provvisorio, con la categoria scelta (o quella delle regole). */
    fun complete(id: Long, amountText: String, description: String, categoryId: Long?, onDone: () -> Unit) {
        val amt = it.spese.core.common.NumberParser.parseMoney(amountText)?.abs()
        if (amt == null || amt.signum() == 0) { messenger.show("Inserisci l'importo."); return }
        viewModelScope.launch {
            try { completePending(id, amt, description, categoryId, null); messenger.show("Movimento aggiunto"); onDone() }
            catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
        }
    }
    fun dismissPending(id: Long) { viewModelScope.launch { repo.update { c -> c.copy(pending = c.pending.filterNot { it.id == id }) } } }
    fun startLearning() = learning.start()
    fun stopLearning() = learning.stop()
    /** Usa un'app rilevata come fonte: aggiunge il pacchetto e accende la fonte. */
    fun useAsSource(s: CaptureSource, packageName: String) = updateSource(s) { it.copy(enabled = true, extraPackages = it.extraPackages + packageName) }
}
