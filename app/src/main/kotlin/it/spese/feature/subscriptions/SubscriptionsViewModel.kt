package it.spese.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.model.RecurringRule
import it.spese.core.model.TxKind
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import it.spese.domain.repository.RecurringRepository
import it.spese.domain.usecase.SaveRecurringUseCase
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IgnoredRow(val key: String, val name: String)

data class SubscriptionsState(
    val data: AppData = AppData(),
    val active: List<RecurringPattern> = emptyList(), val stale: List<RecurringPattern> = emptyList(), val finished: List<RecurringPattern> = emptyList(),
    val ignored: List<IgnoredRow> = emptyList(), val summary: SubscriptionSummary = SubscriptionSummary(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0, 0),
) { val news: List<RecurringPattern> get() = active.filter { it.hasNews } }

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModel @Inject constructor(
    data: StateFlow<AppData>, recurring: RecurringRepository, private val review: SubscriptionReviewRepository,
    private val saveRecurring: SaveRecurringUseCase, private val messenger: UiMessenger,
) : ViewModel() {
    val state: StateFlow<SubscriptionsState> = combine(data, recurring.observeAll(), review.observeIgnored()) { d, rules, ign -> Triple(d, rules, ign) }.mapLatest { (d, rules, ign) ->
        withContext(Dispatchers.Default) {
            val all = SubscriptionDetector.detect(d.transactions, SubscriptionDetector.excludedCategories(d.categories), LocalDate.now(), rules)
            val shown = all.filter { it.key !in ign }
            SubscriptionsState(d, shown.filter { !it.stale && !it.finished }, shown.filter { it.stale }, shown.filter { it.finished },
                ign.sorted().map { k -> IgnoredRow(k, all.firstOrNull { it.key == k }?.merchant ?: k) }, SubscriptionDetector.summary(shown))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubscriptionsState())

    fun ignore(p: RecurringPattern) { viewModelScope.launch { review.ignore(p.key); messenger.show("«${p.merchant}» non sarà più considerato: lo ritrovi in fondo, in «Ignorati»") } }
    fun restore(key: String) { viewModelScope.launch { review.restore(key) } }

    /** Trasforma il rilevamento in una ricorrenza vera: da quel momento l'app crea il movimento alla data giusta e lo considera nelle previsioni. */
    fun track(p: RecurringPattern) = viewModelScope.launch {
        try {
            saveRecurring(RecurringRule(0, p.merchant, TxKind.SPESA, if (p.kind == PatternKind.VARIABLE) p.meanAmount else p.lastAmount, p.accountId, p.categoryId,
                p.cadence.months, p.next.dayOfMonth, p.next, p.endDate))
            messenger.show("Ricorrenza creata: la trovi anche in Ricorrenti")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
