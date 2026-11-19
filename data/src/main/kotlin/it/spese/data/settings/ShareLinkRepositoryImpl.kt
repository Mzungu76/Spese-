package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.sharing.ShareLink
import it.spese.domain.sharing.ShareLinkCodec
import it.spese.domain.sharing.ShareLinkRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.shareStore: DataStore<Preferences> by preferencesDataStore("share_link")

/** La condivisione attiva (codici e chiave) nelle preferenze private del telefono: non finisce nel backup in chiaro. */
@Singleton
class ShareLinkRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : ShareLinkRepository {
    private val key = stringPreferencesKey("link")
    override fun observe(): Flow<ShareLink?> = context.shareStore.data.map { ShareLinkCodec.decode(it[key]) }
    override suspend fun get(): ShareLink? = observe().first()
    override suspend fun set(link: ShareLink?) { context.shareStore.edit { p ->
        if (link == null) { p.remove(key) } else { p[key] = ShareLinkCodec.encode(link) }
    } }
    override suspend fun update(transform: (ShareLink) -> ShareLink) {
        context.shareStore.edit { p -> ShareLinkCodec.decode(p[key])?.let { cur -> p[key] = ShareLinkCodec.encode(transform(cur)) } }
    }
}
