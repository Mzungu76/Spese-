package it.spese.domain.usecase

import it.spese.core.model.DocumentRef
import it.spese.domain.ocr.ReceiptData
import it.spese.domain.ocr.ReceiptParser
import it.spese.domain.repository.DocumentRepository
import it.spese.domain.repository.DocumentStore
import it.spese.domain.repository.TextRecognizer
import it.spese.domain.repository.TransactionRepository
import javax.inject.Inject

data class ScanResult(val document: DocumentRef, val data: ReceiptData)

/**
 * Acquisisce un'immagine (da fotocamera o galleria), la archivia localmente, ne estrae il testo con il motore OCR
 * e lo interpreta. Non crea alcun movimento: l'utente rivede e conferma i dati.
 */
class ScanReceiptUseCase @Inject constructor(
    private val store: DocumentStore, private val documents: DocumentRepository, private val recognizer: TextRecognizer,
) {
    suspend operator fun invoke(imageSource: String): ScanResult {
        val ref = store.saveImage(imageSource)
        val text = try { recognizer.recognize(store.pathOf(ref.fileName)) } catch (e: Exception) { "" }
        val id = documents.add(ref.copy(ocrText = text))
        return ScanResult(ref.copy(id = id, ocrText = text), ReceiptParser.parse(text))
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
