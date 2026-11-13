package it.spese.domain.importing

import it.spese.domain.classification.Duplicates

/** Riga dell'anteprima di importazione. */
data class PreviewRow(val row: ParsedRow, val key: String, val occurrence: Int, val duplicate: Boolean)

data class ImportPreview(val rows: List<PreviewRow>, val skipped: List<SkippedRow>) {
    val newCount: Int get() = rows.count { !it.duplicate }
    val duplicateCount: Int get() = rows.count { it.duplicate }
}

object ImportPlanner {
    /**
     * @param accountName nome del conto di destinazione (entra nella chiave di deduplica, come nella versione HTML)
     * @param existingKeys chiavi dei movimenti già presenti per quel conto (una per movimento)
     */
    fun preview(result: ParseResult, accountName: String, existingKeys: Collection<String>): ImportPreview {
        val planned = Duplicates.plan(existingKeys, result.rows.map { Duplicates.Incoming(keyOf(accountName, it), it) })
        return ImportPreview(planned.map { PreviewRow(it.item, it.key, it.occurrence, it.duplicate) }, result.skipped)
    }

    fun keyOf(accountName: String, r: ParsedRow): String = Duplicates.key(accountName, r.date, r.valueDate, r.amount, r.text)

    /** Identificativo compatibile con la versione HTML: `chiave#n`. */
    fun externalId(p: PreviewRow): String = p.key + "#" + p.occurrence
}
