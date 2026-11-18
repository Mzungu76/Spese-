package it.spese.domain.health

import kotlinx.coroutines.flow.Flow

/** Correzioni dell'utente alla natura (fissa/variabile) di una categoria di entrata. Senza correzione vale il riconoscimento automatico. */
interface IncomeNatureRepository {
    fun observe(): Flow<Map<Long, IncomeNature>>
    suspend fun get(): Map<Long, IncomeNature>
    /** `null` = torna al riconoscimento automatico. */
    suspend fun set(categoryId: Long, nature: IncomeNature?)
    suspend fun setAll(map: Map<Long, IncomeNature>)
}
