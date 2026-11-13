package it.spese.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import it.spese.core.model.DocumentRef
import it.spese.domain.repository.DocumentStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Archivio locale delle fotografie dei documenti, nella memoria privata dell'app (`filesDir/documents`):
 * non sono visibili ad altre app, non vengono inviate online e vengono rimosse con la disinstallazione.
 * Le immagini sono ridimensionate (lato massimo 2000 px) e compresse in JPEG per risparmiare spazio.
 */
@Singleton
class DocumentFiles @Inject constructor(@ApplicationContext private val context: Context) : DocumentStore {
    private val dir: File get() = File(context.filesDir, "documents").also { it.mkdirs() }

    private fun open(source: String): InputStream =
        if (source.startsWith("content://") || source.startsWith("file://")) context.contentResolver.openInputStream(Uri.parse(source)) ?: error("File non leggibile")
        else File(source).inputStream()

    override suspend fun saveImage(source: String): DocumentRef = withContext(Dispatchers.IO) {
        val bytes = open(source).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Immagine non valida")
        val rotation = try {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        } catch (e: Exception) { 0f }
        val maxSide = maxOf(bmp.width, bmp.height)
        val scale = if (maxSide > 2000) 2000f / maxSide else 1f
        if (rotation != 0f || scale != 1f) {
            val m = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val name = "doc_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}.jpg"
        val f = File(dir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        DocumentRef(0, name, "image/jpeg", f.length(), System.currentTimeMillis())
    }

    /** Scrive un file ricevuto da un backup completo. */
    suspend fun writeFile(fileName: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        File(dir, File(fileName).name).writeBytes(bytes)
    }

    fun readFile(fileName: String): ByteArray? = File(dir, File(fileName).name).takeIf { it.exists() }?.readBytes()

    override suspend fun deleteFile(fileName: String) { withContext(Dispatchers.IO) { File(dir, File(fileName).name).delete() } }
    override suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { dir.listFiles()?.sumOf { it.length() } ?: 0L }
    override fun pathOf(fileName: String): String = File(dir, File(fileName).name).absolutePath
    fun allFiles(): List<File> = dir.listFiles()?.toList() ?: emptyList()
}
