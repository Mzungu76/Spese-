package it.spese.app.common

import it.spese.domain.widgets.LayoutOps
import it.spese.domain.widgets.WidgetInstance
import it.spese.domain.widgets.WidgetLayoutRepository
import it.spese.domain.widgets.WidgetPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** La composizione a widget di una pagina (quella scelta dall'utente, o la predefinita), come stato pronto per una schermata. */
@Singleton
class PageLayouts @Inject constructor(private val repo: WidgetLayoutRepository) {
    fun state(page: WidgetPage, scope: CoroutineScope): StateFlow<List<WidgetInstance>> =
        repo.observe().map { m -> LayoutOps.effective(m, page) }.stateIn(scope, SharingStarted.WhileSubscribed(5000), LayoutOps.default(page))
}
