package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import it.spese.core.common.DecimalStyle
import it.spese.domain.importing.ColumnRole
import it.spese.domain.importing.DateOrder
import it.spese.domain.importing.ImportOptions
import it.spese.domain.repository.ImportProfile
import it.spese.domain.repository.ImportProfileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore("import_profiles")

/** Formati di estratto conto ricordati (solo mappatura delle colonne, nessun dato finanziario). */
@Singleton
class ImportProfileRepositoryImpl @Inject constructor(@ApplicationContext private val context: Context) : ImportProfileRepository {
    private val key = stringPreferencesKey("profiles")

    override suspend fun all(): List<ImportProfile> {
        val txt = context.profileStore.data.first()[key] ?: return emptyList()
        return try {
            Json.parseToJsonElement(txt).jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                ImportProfile(
                    o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null, o["sig"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    o["roles"]?.jsonArray?.map { r -> runCatching { ColumnRole.valueOf(r.jsonPrimitive.content) }.getOrDefault(ColumnRole.IGNORE) } ?: return@mapNotNull null,
                    ImportOptions(
                        runCatching { DateOrder.valueOf(o["dateOrder"]!!.jsonPrimitive.content) }.getOrDefault(DateOrder.AUTO),
                        runCatching { DecimalStyle.valueOf(o["decimal"]!!.jsonPrimitive.content) }.getOrDefault(DecimalStyle.AUTO),
                        o["invert"]?.jsonPrimitive?.booleanOrNull ?: false, o["skipLast"]?.jsonPrimitive?.intOrNull ?: 0,
                    ),
                    o["header"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun save(profile: ImportProfile) = write(all().filter { it.signature != profile.signature } + profile)
    override suspend fun delete(signature: String) = write(all().filter { it.signature != signature })

    private suspend fun write(list: List<ImportProfile>) {
        val arr = buildJsonArray {
            list.forEach { p -> add(buildJsonObject {
                put("name", p.name); put("sig", p.signature); put("header", p.headerRow)
                put("roles", buildJsonArray { p.roles.forEach { add(JsonPrimitive(it.name)) } })
                put("dateOrder", p.options.dateOrder.name); put("decimal", p.options.decimal.name); put("invert", p.options.invertSign); put("skipLast", p.options.skipLast)
            }) }
        }
        context.profileStore.edit { it[key] = arr.toString() }
    }
}
