package it.spese.domain.classification

import it.spese.core.model.Category
import it.spese.core.model.TxKind
import it.spese.domain.repository.AppData

/** Ricordare quali coppie l'utente ha detto «non è un giroconto» (solo sul telefono). */
interface TransferReviewRepository {
    fun observeIgnored(): kotlinx.coroutines.flow.Flow<Set<String>>
    suspend fun getIgnored(): Set<String>
    suspend fun ignore(key: String)
    suspend fun setIgnored(keys: Set<String>)
}

/** Giroconti probabili da far confermare: coppie di movimenti interi (non divisi, non già classificati a mano o come trasferimento) e non scartate. */
object TransferSuggestions {
    fun compute(data: AppData, ignored: Set<String>, maxDays: Int = 3): List<TransferDetector.Pair> {
        if (!data.loaded) return emptyList()
        val ledger = data.ledger.filter { it.id !in data.splits && !it.manualCategory }
        return TransferDetector.detect(ledger, maxDays).filter { it.key !in ignored }.sortedByDescending { it.outgoing.bookingDate }
    }

    /** La categoria dei trasferimenti: quella chiamata «Trasferimenti», altrimenti la prima attiva di tipo trasferimento. */
    fun transferCategory(categories: List<Category>): Category? = categories.filter { it.active && it.kind == TxKind.TRASFERIMENTO }
        .let { l -> l.firstOrNull { it.name.equals("Trasferimenti", ignoreCase = true) } ?: l.firstOrNull() }
}
