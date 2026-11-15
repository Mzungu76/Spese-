package it.spese.app.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import it.spese.domain.ocr.OcrLayout
import it.spese.domain.ocr.OcrLine
import it.spese.domain.repository.TextRecognizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * OCR on-device con ML Kit (modello latino incluso nell'APK: funziona senza connessione e senza servizi Google).
 * L'interfaccia [TextRecognizer] permette di sostituire il motore o aggiungere analisi multipagina in futuro.
 */
@Singleton
class MlKitTextRecognizer @Inject constructor() : TextRecognizer {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(imagePath: String): String {
        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagePath) } ?: error("Immagine non leggibile")
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r ->
                    // righe ricostruite dalla posizione (raddrizzando l'inclinazione): descrizione, IVA e prezzo tornano sulla stessa riga
                    val ocr = r.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                        val c = l.cornerPoints?.takeIf { it.size == 4 } ?: return@mapNotNull null
                        OcrLine(l.text, c.map { it.x }.average().toFloat(), c.map { it.y }.average().toFloat(),
                            OcrLayout.angle(c[0].x.toFloat(), c[0].y.toFloat(), c[1].x.toFloat(), c[1].y.toFloat()),
                            kotlin.math.hypot((c[3].x - c[0].x).toFloat(), (c[3].y - c[0].y).toFloat()))
                    }
                    cont.resume(if (ocr.isEmpty()) r.text else OcrLayout.rows(ocr))
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}
