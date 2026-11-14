package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.repository.ReportPresetRepository
import it.spese.domain.report.ReportSpecCodec
import it.spese.domain.report.SavedReport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.reportStore: DataStore<Preferences> by preferencesDataStore("reports")

/** Modelli di report dell'utente: nome e scelte, senza alcun dato finanziario. */
@Singleton
class ReportPresetRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : ReportPresetRepository {
    private val key = stringPreferencesKey("saved")
    override fun observe(): Flow<List<SavedReport>> = context.reportStore.data.map { ReportSpecCodec.decodeList(it[key]) }
    override suspend fun save(r: SavedReport) {
        val cur = observe().first().filter { it.id != r.id }
        context.reportStore.edit { it[key] = ReportSpecCodec.encodeList(cur + r) }
    }
    override suspend fun delete(id: String) {
        val cur = observe().first().filter { it.id != id }
        context.reportStore.edit { it[key] = ReportSpecCodec.encodeList(cur) }
    }
}
