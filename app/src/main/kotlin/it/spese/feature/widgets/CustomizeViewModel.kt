package it.spese.feature.widgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.widgets.LayoutOps
import it.spese.domain.widgets.LayoutPresets
import it.spese.domain.widgets.WidgetCatalog
import it.spese.domain.widgets.WidgetInstance
import it.spese.domain.widgets.WidgetLayoutRepository
import it.spese.domain.widgets.WidgetPage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** La composizione di una pagina e le modifiche che l'utente può farle (ordine, visibilità, aggiunte, setup pronti). */
@HiltViewModel
class CustomizeViewModel @Inject constructor(savedState: SavedStateHandle, private val repo: WidgetLayoutRepository, private val messenger: UiMessenger) : ViewModel() {
    private val _page = MutableStateFlow(WidgetPage.entries.firstOrNull { p -> p.name == savedState.get<String>("page") } ?: WidgetPage.HOME)
    val page: StateFlow<WidgetPage> = _page
    fun setPage(p: WidgetPage) { _page.value = p }

    val layout: StateFlow<List<WidgetInstance>> = combine(repo.observe(), _page) { m, p -> LayoutOps.effective(m, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LayoutOps.default(_page.value))
    /** true se la pagina è stata personalizzata (c'è una composizione salvata). */
    val customized: StateFlow<Boolean> = combine(repo.observe(), _page) { m, p -> m.containsKey(p) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private fun edit(t: (List<WidgetInstance>) -> List<WidgetInstance>) = viewModelScope.launch {
        val p = _page.value
        repo.set(p, t(LayoutOps.effective(repo.get(), p)))
    }

    fun move(uid: String, delta: Int) = edit { l -> LayoutOps.move(l, uid, delta) }
    fun setEnabled(uid: String, on: Boolean) = edit { l -> LayoutOps.setEnabled(l, uid, on) }
    fun remove(uid: String) = edit { l -> LayoutOps.remove(l, uid) }
    fun add(widgetId: String) = edit { l ->
        LayoutOps.add(l, widgetId, if (widgetId == WidgetCatalog.SECTION) mapOf("title" to "Nuova sezione") else emptyMap())
    }
    fun setOption(uid: String, key: String, value: String?) = edit { l -> LayoutOps.setOption(l, uid, key, value) }

    /** Applica un setup alla pagina che stai modificando. */
    fun applyPreset(id: String) = viewModelScope.launch {
        val p = LayoutPresets.byId(id) ?: return@launch
        repo.set(_page.value, LayoutPresets.instances(p, _page.value))
        messenger.show("Setup «${p.title}» applicato a ${_page.value.label}")
    }

    /** Applica lo stesso setup a tutte le pagine. */
    fun applyPresetEverywhere(id: String) = viewModelScope.launch {
        val p = LayoutPresets.byId(id) ?: return@launch
        WidgetPage.entries.forEach { page -> if (p.layouts.containsKey(page)) repo.set(page, LayoutPresets.instances(p, page)) }
        messenger.show("Setup «${p.title}» applicato a tutte le pagine")
    }

    fun reset() = viewModelScope.launch { repo.reset(_page.value); messenger.show("Composizione predefinita ripristinata") }
}
