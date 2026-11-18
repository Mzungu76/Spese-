package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.repository.ManagedAccountsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.managedStore: DataStore<Preferences> by preferencesDataStore("managed_accounts")

/** Id dei conti gestiti da altri, nelle preferenze del telefono (e nel backup, per nome). */
@Singleton
class ManagedAccountsRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : ManagedAccountsRepository {
    private val key = stringSetPreferencesKey("ids")
    private fun decode(s: Set<String>?): Set<Long> = (s ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    override fun observe(): Flow<Set<Long>> = context.managedStore.data.map { decode(it[key]) }
    override suspend fun get(): Set<Long> = observe().first()
    override suspend fun set(accountId: Long, managed: Boolean) {
        context.managedStore.edit { p -> val cur = decode(p[key]); p[key] = (if (managed) cur + accountId else cur - accountId).map { it.toString() }.toSet() }
    }
    override suspend fun setAll(ids: Set<Long>) { context.managedStore.edit { it[key] = ids.map { id -> id.toString() }.toSet() } }
}
