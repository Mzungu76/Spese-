package it.spese.domain.ocr

import java.text.Normalizer

/**
 * Memoria "voce di scontrino → categoria": quando l'utente assegna una categoria a una voce, l'app la ricorda
 * e la propone da sola agli scontrini successivi. Solo sul telefono, nessun dato esce.
 */
object ItemMemory {
    private const val MAX_ENTRIES = 1500

    /** Chiave normalizzata: minuscolo, senza accenti, numeri, pesi/quantità e punteggiatura ("LATTE P.S. 1L" → "latte p s"). */
    fun key(description: String): String {
        val d = Normalizer.normalize(description.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return d.replace(Regex("\\b\\d+([.,]\\d+)?\\s*(kg|gr|g|ml|cl|lt|l|pz|x)?\\b"), " ")
            .replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Categoria proposta per ogni voce: corrispondenza esatta, altrimenti una voce ricordata contenuta nel testo (almeno 4 lettere). */
    fun suggest(descriptions: List<String>, memory: Map<String, Long>, validCategoryIds: Set<Long>): List<Long?> =
        descriptions.map { d ->
            val k = key(d)
            if (k.isBlank()) return@map null
            val hit = memory[k] ?: memory.entries.filter { (m, _) -> m.length >= 4 && (k.contains(m) || m.contains(k) && k.length >= 4) }
                .maxByOrNull { it.key.length }?.value
            hit?.takeIf { it in validCategoryIds }
        }

    /** Aggiunge le scelte (le più recenti in coda) e tiene al massimo [MAX_ENTRIES] voci. */
    fun merge(memory: Map<String, Long>, learned: Map<String, Long>): Map<String, Long> {
        val out = LinkedHashMap(memory)
        learned.forEach { (k, v) -> out.remove(k); out[k] = v }
        return if (out.size <= MAX_ENTRIES) out else out.entries.toList().takeLast(MAX_ENTRIES).associate { it.key to it.value }
    }
}

/** Memoria delle voci di scontrino (DataStore sul telefono). */
interface ItemMemoryRepository {
    suspend fun get(): Map<String, Long>
    suspend fun learn(entries: Map<String, Long>)
    suspend fun clear()
}
