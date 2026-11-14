package it.spese.feature.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.model.RecurringRule
import it.spese.domain.repository.AppData
import it.spese.domain.repository.RecurringRepository
import it.spese.domain.usecase.SaveRecurringUseCase
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RecurringViewModel @Inject constructor(
    val data: StateFlow<AppData>, repo: RecurringRepository, private val saveUc: SaveRecurringUseCase, private val recurring: RecurringRepository, private val messenger: UiMessenger,
) : ViewModel() {
    val rules: StateFlow<List<RecurringRule>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(r: RecurringRule) = viewModelScope.launch {
        try {
            val n = saveUc(r)
            if (n > 0) messenger.show(if (n == 1) "Creato 1 movimento: controllalo in Movimenti › Verifica" else "Creati $n movimenti: controllali in Movimenti › Verifica")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
    fun setEnabled(r: RecurringRule, on: Boolean) = save(r.copy(enabled = on))
    fun delete(id: Long) = viewModelScope.launch { recurring.delete(id); messenger.show("Eliminato: i movimenti già creati restano") }
}
