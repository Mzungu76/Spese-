package it.spese.domain.usecase

import it.spese.core.common.Money
import it.spese.core.model.DocumentRef
import it.spese.core.model.Transaction
import it.spese.core.model.TxOrigin
import it.spese.domain.ocr.ReceiptItem
import it.spese.domain.ocr.ReceiptStitcher
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import it.spese.domain.ocr.ReceiptData
import it.spese.domain.ocr.ReceiptParser
import it.spese.domain.repository.CategoryRepository
import it.spese.domain.repository.ClassificationUpdate
import it.spese.domain.repository.DocumentRepository
import it.spese.domain.repository.DocumentStore
import it.spese.domain.repository.TextRecognizer
import it.spese.domain.repository.TransactionRepository
import javax.inject.Inject

/** `pages` = quante foto compongono lo scontrino; `overlaps[i]` = righe in comune trovate tra la foto i e la i+1 (0 = nessuna: possibili righe mancanti). */
data class ScanResult(val document: DocumentRef, val data: ReceiptData, val pages: Int = 1, val overlaps: List<Int> = emptyList())

/**
 * Acquisisce un'immagine (da fotocamera o galleria), la archivia localmente, ne estrae il testo con il motore OCR
 * e lo interpreta. Non crea alcun movimento: l'utente rivede e conferma i dati.
 */
class ScanReceiptUseCase @Inject constructor(
    private val store: DocumentStore, private val documents: DocumentRepository, private val recognizer: TextRecognizer,
) {
    suspend operator fun invoke(imageSource: String): ScanResult = invoke(listOf(imageSource))

    /**
     * Più foto dello stesso scontrino (dall'alto verso il basso): ognuna viene riconosciuta, i testi sono uniti togliendo le righe
     * in comune dove le foto si sovrappongono, e le immagini sono archiviate come un unico documento lungo.
     */
    suspend operator fun invoke(imageSources: List<String>): ScanResult {
        require(imageSources.isNotEmpty()) { "Nessuna foto." }
        val refs = ArrayList<DocumentRef>()
        try {
            for (src in imageSources) refs.add(store.saveImage(src))
            val texts = refs.map { r -> try { recognizer.recognize(store.pathOf(r.fileName)) } catch (e: Exception) { "" } }
            val merged = if (refs.size == 1) ReceiptStitcher.merge(texts).let { it.copy(text = texts[0]) } else ReceiptStitcher.merge(texts)
            val final = if (refs.size == 1) refs[0] else store.stitch(refs).also { refs.forEach { r -> store.deleteFile(r.fileName) } }
            val id = documents.add(final.copy(ocrText = merged.text))
            return ScanResult(final.copy(id = id, ocrText = merged.text), ReceiptParser.parse(merged.text), refs.size, merged.overlaps)
        } catch (e: Exception) {
            refs.forEach { r -> try { store.deleteFile(r.fileName) } catch (_: Exception) { } }
            throw e
        }
    }
}

/** Elimina un documento (file + scheda) e lo scollega dal movimento. */
class DeleteDocumentUseCase @Inject constructor(
    private val store: DocumentStore, private val documents: DocumentRepository,
) {
    suspend operator fun invoke(id: Long) {
        val d = documents.get(id) ?: return
        store.deleteFile(d.fileName)
        documents.delete(id)
    }
}

/** Dalle voci dello scontrino alle parti della divisione: ogni categoria scelta per le voci diventa una parte, il resto resta alla categoria principale. */
object ReceiptSplitPlanner {
    /** Le voci sommano più del totale (righe doppie, prezzi letti male): non si può usare il resto, si ripartisce il totale in proporzione. */
    fun isProportional(total: BigDecimal, items: List<ReceiptItem>): Boolean =
        items.fold(BigDecimal.ZERO) { a, b -> a + b.amount.abs() } > total.abs() + BigDecimal("0.02")

    /** @param itemCategories categoria scelta per ogni voce (stesso ordine di `items`; null = resta alla categoria principale)
     *  @return lista vuota se nessuna voce ha una categoria diversa dalla principale; altrimenti le parti (con importo col segno del movimento).
     *  Se le voci superano il totale, ogni categoria prende la sua quota percentuale del totale (le parti sommano sempre il totale). */
    fun plan(total: BigDecimal, items: List<ReceiptItem>, itemCategories: List<Long?>, mainCategoryId: Long?, expense: Boolean): List<SplitPartInput> {
        val sign = BigDecimal(if (expense) -1 else 1)
        val sum = items.fold(BigDecimal.ZERO) { a, b -> a + b.amount.abs() }
        if (items.indices.none { i -> itemCategories.getOrNull(i).let { c -> c != null && c != mainCategoryId } }) return emptyList()
        if (isProportional(total, items)) {
            val byCat = LinkedHashMap<Long?, BigDecimal>()
            items.forEachIndexed { i, it -> byCat.merge(itemCategories.getOrNull(i) ?: mainCategoryId, it.amount.abs()) { a, b -> a + b } }
            if (byCat.size < 2) return emptyList()
            val entries = byCat.entries.toList()
            val parts = ArrayList<SplitPartInput>(); var used = BigDecimal.ZERO
            entries.forEachIndexed { k, (c, v) ->
                val share = if (k == entries.lastIndex) total.abs() - used else Money.scale(total.abs().multiply(v).divide(sum, 10, java.math.RoundingMode.HALF_UP))
                used += share
                if (share.signum() > 0) parts.add(SplitPartInput(c, share.multiply(sign)))
            }
            return parts
        }
        val moved = LinkedHashMap<Long, BigDecimal>()
        items.forEachIndexed { i, it ->
            val c = itemCategories.getOrNull(i) ?: return@forEachIndexed
            if (c != mainCategoryId) moved.merge(c, it.amount.abs()) { a, b -> a + b }
        }
        if (moved.isEmpty()) return emptyList()
        val movedSum = moved.values.fold(BigDecimal.ZERO) { a, b -> a + b }
        val rest = total.abs().subtract(movedSum)
        val parts = moved.map { (c, v) -> SplitPartInput(c, Money.scale(v).multiply(sign)) }.toMutableList()
        if (rest.signum() > 0) parts.add(0, SplitPartInput(mainCategoryId, Money.scale(rest).multiply(sign)))
        return parts
    }
}

/** Voci ripetute: un blocco di almeno 3 voci uguali a quello appena prima è quasi sempre la sovrapposizione tra due foto non riconosciuta. */
object ReceiptDedupe {
    fun repeatedBlocks(items: List<ReceiptItem>): Set<Int> {
        val keys = items.map { ReceiptStitcher.key(it.description) }
        val out = HashSet<Int>()
        var i = 0
        while (i < items.size) {
            var found = 0
            for (len in minOf((items.size - i) / 2, 60) downTo 3) {
                if ((0 until len).all { k -> keys[i + k].isNotEmpty() && keys[i + k] == keys[i + len + k] }) { found = len; break }
            }
            if (found > 0) { for (k in 0 until found) out.add(i + found + k); i += 2 * found } else i++
        }
        return out
    }
}

/** Movimenti già presenti (da estratto conto, notifica o manuali) che corrispondono a uno scontrino: stesso importo, data vicina, senza documento. */
object ReceiptMatcher {
    fun candidates(ledger: List<Transaction>, amount: BigDecimal, date: LocalDate, days: Int = 5): List<Transaction> =
        ledger.filter { it.documentId == null && it.origin != TxOrigin.OCR && it.amount.compareTo(amount) == 0 }
            .map { it to Math.abs(ChronoUnit.DAYS.between(it.bookingDate, date)).toInt() }
            .filter { it.second <= days }.sortedBy { it.second }.map { it.first }
}

/**
 * Salva lo scontrino. Con `attachToId` non crea un doppione: allega il documento al movimento esistente (quello dell'estratto conto)
 * e vi applica la suddivisione per voci. Altrimenti crea un movimento provvisorio (origine scontrino), sostituito dalla riga
 * dell'estratto conto alla prossima importazione. Restituisce l'id del movimento che contiene lo scontrino.
 */
class SaveReceiptUseCase @Inject constructor(
    private val saveTx: SaveTransactionUseCase, private val tx: TransactionRepository, private val setSplits: SetSplitsUseCase,
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(draft: TransactionDraft, documentId: Long, parts: List<SplitPartInput>, attachToId: Long? = null): Long {
        val id = if (attachToId != null) {
            val t = tx.get(attachToId) ?: error("Movimento non trovato")
            tx.setDocument(attachToId, documentId)
            if (draft.notes.isNotBlank() && !t.notes.contains(draft.notes)) tx.update(t.copy(notes = listOfNotNull(t.notes.takeIf { it.isNotBlank() }, draft.notes).joinToString("\n"), documentId = documentId), it.spese.domain.classification.Duplicates.key(t.accountId.toString(), t.bookingDate, t.valueDate, t.amount, t.description))
            attachToId
        } else {
            saveTx(draft.copy(documentId = documentId, origin = TxOrigin.OCR))
            tx.getAll().filter { it.documentId == documentId }.maxByOrNull { it.id }?.id ?: error("Movimento non salvato")
        }
        when {
            parts.size >= 2 -> setSplits(id, parts)
            // tutte le voci in un'unica categoria: niente divisione, la categoria va al movimento
            parts.size == 1 -> parts[0].categoryId?.let { c ->
                val kind = categories.getAll().firstOrNull { it.id == c }?.kind
                if (kind != null) tx.applyClassifications(listOf(ClassificationUpdate(id, c, kind, true, null)))
            }
        }
        return id
    }
}
