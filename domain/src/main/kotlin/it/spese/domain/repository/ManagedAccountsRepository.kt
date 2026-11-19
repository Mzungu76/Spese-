package it.spese.domain.repository

import kotlinx.coroutines.flow.Flow

/** Conti «gestiti da altri» (es. carta di famiglia): nel patrimonio, ma fuori da budget, salute del bilancio, ritmo e medie. */
interface ManagedAccountsRepository {
    fun observe(): Flow<Set<Long>>
    suspend fun get(): Set<Long>
    suspend fun set(accountId: Long, managed: Boolean)
    suspend fun setAll(ids: Set<Long>)
}
