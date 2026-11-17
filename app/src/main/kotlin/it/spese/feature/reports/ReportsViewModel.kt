package it.spese.feature.reports

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.repository.AppData
import it.spese.domain.repository.ReportPresetRepository
import it.spese.domain.report.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un report pronto: il PDF e le immagini delle pagine per l'anteprima (vuote se il telefono non sa mostrarle). */
class GeneratedReport(val bytes: ByteArray, val fileName: String, val pages: List<Bitmap>, val pageCount: Int, val empty: Boolean)

data class ReportsState(
    val editing: Boolean = false, val name: String = "", val spec: ReportSpec = ReportSpec(), val sourceId: String? = null,
    val busy: Boolean = false, val result: GeneratedReport? = null,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(app: Application, val data: StateFlow<AppData>, private val presets: ReportPresetRepository, private val messenger: UiMessenger) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()
    val saved: StateFlow<List<SavedReport>> = presets.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startPreset(p: ReportPreset) { _state.value = ReportsState(true, p.name, p.spec, p.id) }
    fun startSaved(r: SavedReport) { _state.value = ReportsState(true, r.name, r.spec, r.id) }
    fun startBlank() { _state.value = ReportsState(true, "Report personalizzato", ReportSpec(sections = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES)), null) }
    fun backToList() { _state.value = ReportsState() }
    fun edit(t: (ReportSpec) -> ReportSpec) { _state.update { it.copy(spec = t(it.spec), result = null) } }

    fun deleteSaved(id: String) = viewModelScope.launch { presets.delete(id) }
    fun saveAs(name: String) = viewModelScope.launch {
        val n = name.trim().ifBlank { "Il mio report" }
        presets.save(SavedReport(UUID.randomUUID().toString(), n, _state.value.spec))
        _state.update { it.copy(name = n) }
        messenger.show("Modello «$n» salvato")
    }

    /** Costruisce il report, ne fa il PDF e le immagini di anteprima. */
    fun generate() = viewModelScope.launch {
        val st = _state.value
        _state.update { it.copy(busy = true, result = null) }
        try {
            val r = withContext(Dispatchers.Default) {
                val doc = ReportBuilder.build(data.value, st.spec, LocalDate.now(), st.name)
                val pdf = PdfReportRenderer.render(doc)
                val pv = runCatching { previews(pdf.bytes) }.getOrNull()
                GeneratedReport(pdf.bytes, fileName(doc.title), pv?.first ?: emptyList(), pdf.pageCount, doc.empty)
            }
            _state.update { it.copy(busy = false, result = r) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false) }
            messenger.show("Impossibile creare il report: " + (e.message ?: "errore"))
        }
    }

    fun saveTo(uri: Uri) = viewModelScope.launch {
        val r = _state.value.result ?: return@launch
        try { withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { it.write(r.bytes) } }; messenger.show("PDF salvato") }
        catch (e: Exception) { messenger.show("Salvataggio non riuscito: " + (e.message ?: "errore")) }
    }

    private fun fileName(title: String): String {
        val slug = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "report" }
        return "$slug-${it.spese.app.common.stamp()}.pdf"
    }

    /** Immagini delle pagine (fino a 12) con PdfRenderer; e numero totale di pagine. */
    private fun previews(bytes: ByteArray): Pair<List<Bitmap>, Int> {
        val dir = File(getApplication<Application>().cacheDir, "reports").also { it.mkdirs() }
        val f = File(dir, "preview.pdf").also { it.writeBytes(bytes) }
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                val out = (0 until minOf(r.pageCount, 12)).map { i ->
                    r.openPage(i).use { p ->
                        val w = 900; val h = (w.toFloat() * p.height / p.width).toInt()
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                }
                return out to r.pageCount
            }
        }
    }
}
