package it.spese.feature.widgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.widgets.LayoutOps
import it.spese.domain.widgets.LayoutPresets
import it.spese.domain.widgets.WidgetInstance
import it.spese.domain.widgets.WidgetLayoutRepository
import it.spese.domain.widgets.WidgetPage
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** La composizione di una pagina e le modifiche che l'utente può farle (ordine, visibilità, aggiunte, setup pronti). */
@HiltViewModel
class CustomizeViewModel @Inject constructor(private val repo: WidgetLayoutRepository, private val messenger: UiMessenger) : ViewModel() {
    private val page = WidgetPage.HOME

    val layout: StateFlow<List<WidgetInstance>> = repo.observe().map { m -> LayoutOps.effective(m, page) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LayoutOps.default(page))
    /** true se la pagina è stata personalizzata (c'è una composizione salvata). */
    val customized: StateFlow<Boolean> = repo.observe().map { m -> m.containsKey(page) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private fun edit(t: (List<WidgetInstance>) -> List<WidgetInstance>) = viewModelScope.launch {
        repo.set(page, t(LayoutOps.effective(repo.get(), page)))
    }

    fun move(uid: String, delta: Int) = edit { l -> LayoutOps.move(l, uid, delta) }
    fun setEnabled(uid: String, on: Boolean) = edit { l -> LayoutOps.setEnabled(l, uid, on) }
    fun remove(uid: String) = edit { l -> LayoutOps.remove(l, uid) }
    fun add(widgetId: String) = edit { l ->
        LayoutOps.add(l, widgetId, if (widgetId == it.spese.domain.widgets.WidgetCatalog.SECTION) mapOf("title" to "Nuova sezione") else emptyMap())
    }
    fun setOption(uid: String, key: String, value: String?) = edit { l -> LayoutOps.setOption(l, uid, key, value) }

    fun applyPreset(id: String) = viewModelScope.launch {
        val p = LayoutPresets.byId(id) ?: return@launch
        repo.set(page, LayoutPresets.instances(p, page))
        messenger.show("Setup «${p.title}» applicato")
    }

    fun reset() = viewModelScope.launch { repo.reset(page); messenger.show("Composizione predefinita ripristinata") }
}
