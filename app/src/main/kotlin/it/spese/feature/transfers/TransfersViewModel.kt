package it.spese.feature.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.classification.TransferDetector
import it.spese.domain.classification.TransferReviewRepository
import it.spese.domain.classification.TransferSuggestions
import it.spese.domain.repository.AppData
import it.spese.domain.usecase.ConfirmTransfersUseCase
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TransfersViewModel @Inject constructor(
    val data: StateFlow<AppData>, private val review: TransferReviewRepository, private val confirmUc: ConfirmTransfersUseCase, private val messenger: UiMessenger,
) : ViewModel() {
    /** Coppie di movimenti che sembrano uno spostamento tra due tuoi conti, da confermare. */
    val pairs: StateFlow<List<TransferDetector.Pair>> = combine(data, review.observeIgnored()) { d, ign -> runCatching { TransferSuggestions.compute(d, ign) }.getOrDefault(emptyList()) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun confirm(pairs: Collection<TransferDetector.Pair>) = viewModelScope.launch {
        try {
            val n = confirmUc(pairs)
            if (n > 0) messenger.show(if (n == 1) "Giroconto confermato: esce da entrate e spese" else "$n giroconti confermati: escono da entrate e spese")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }

    fun dismiss(p: TransferDetector.Pair) = viewModelScope.launch { review.ignore(p.key); messenger.show("Non sarà più proposto come giroconto") }
}
