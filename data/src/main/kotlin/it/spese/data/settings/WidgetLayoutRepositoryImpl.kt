package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.widgets.LayoutCodec
import it.spese.domain.widgets.WidgetInstance
import it.spese.domain.widgets.WidgetLayoutRepository
import it.spese.domain.widgets.WidgetPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.widgetStore: DataStore<Preferences> by preferencesDataStore("widget_layouts")

/** Le composizioni dei widget, un testo JSON nelle preferenze del telefono (e nel backup). */
@Singleton
class WidgetLayoutRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : WidgetLayoutRepository {
    private val key = stringPreferencesKey("layouts")
    override fun observe(): Flow<Map<WidgetPage, List<WidgetInstance>>> = context.widgetStore.data.map { LayoutCodec.decode(it[key]) }
    override suspend fun get(): Map<WidgetPage, List<WidgetInstance>> = observe().first()
    override suspend fun set(page: WidgetPage, items: List<WidgetInstance>) {
        context.widgetStore.edit { p -> p[key] = LayoutCodec.encode(LayoutCodec.decode(p[key]) + (page to items)) }
    }
    override suspend fun reset(page: WidgetPage) {
        context.widgetStore.edit { p -> p[key] = LayoutCodec.encode(LayoutCodec.decode(p[key]) - page) }
    }
    override suspend fun setAll(map: Map<WidgetPage, List<WidgetInstance>>) { context.widgetStore.edit { it[key] = LayoutCodec.encode(map) } }
}
