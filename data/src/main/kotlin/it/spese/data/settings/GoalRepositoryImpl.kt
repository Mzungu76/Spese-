package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.core.model.Goal
import it.spese.domain.goals.GoalCodec
import it.spese.domain.repository.GoalRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.goalStore: DataStore<Preferences> by preferencesDataStore("goals")

/** Obiettivi di risparmio: nome, importi e scadenza, salvati sul telefono insieme alle altre preferenze (e inclusi nel backup). */
@Singleton
class GoalRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : GoalRepository {
    private val key = stringPreferencesKey("goals")
    override fun observeAll(): Flow<List<Goal>> = context.goalStore.data.map { GoalCodec.decode(it[key]) }
    override suspend fun getAll(): List<Goal> = observeAll().first()

    override suspend fun save(g: Goal): Long {
        var id = g.id
        context.goalStore.edit { p ->
            val cur = GoalCodec.decode(p[key])
            if (id == 0L) id = (cur.maxOfOrNull { it.id } ?: 0L) + 1
            p[key] = GoalCodec.encode(cur.filter { it.id != id } + g.copy(id = id, name = g.name.trim()))
        }
        return id
    }

    override suspend fun delete(id: Long) { context.goalStore.edit { p -> p[key] = GoalCodec.encode(GoalCodec.decode(p[key]).filter { it.id != id }) } }

    override suspend fun setAll(goals: List<Goal>) {
        context.goalStore.edit { p -> p[key] = GoalCodec.encode(goals.mapIndexed { i, g -> g.copy(id = i + 1L) }) }
    }
}
