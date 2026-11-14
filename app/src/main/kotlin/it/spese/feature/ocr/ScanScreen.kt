package it.spese.feature.ocr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.FileImage
import it.spese.app.common.SubTopBar
import it.spese.core.common.Money
import it.spese.domain.finance.CategoryTree
import it.spese.core.designsystem.component.*
import java.io.File

@Composable
fun ScanScreen(onBack: () -> Unit, vm: ScanViewModel = hiltViewModel()) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.saved.collect { onBack() } }
    Scaffold(topBar = { SubTopBar("Scansiona documento", onBack) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val s = stage) {
                ScanStage.Capture -> CaptureView(vm)
                ScanStage.Processing -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text("Riconoscimento del testo sul dispositivo…")
                }
                is ScanStage.Failed -> EmptyState(Icons.Default.ErrorOutline, "Scansione non riuscita", s.message) { Button(onClick = vm::back) { Text("Riprova") } }
                is ScanStage.Review -> ReviewView(s, vm)
            }
        }
    }
}

@Composable
private fun CaptureView(vm: ScanViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.process(it.toString()) } }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (granted) {
            val controller = remember { LifecycleCameraController(ctx).apply { setEnabledUseCases(CameraController.IMAGE_CAPTURE) } }
            LaunchedEffect(controller) { controller.bindToLifecycle(owner) }
            DisposableEffect(controller) { onDispose { controller.unbind() } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView({ c -> PreviewView(c).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FIT_CENTER } }, Modifier.fillMaxSize())
            }
            Text("Inquadra lo scontrino, la fattura o la ricevuta: tienilo piano e ben illuminato.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { gallery.launch("image/*") }, Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Galleria") }
                Button(onClick = {
                    val f = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                    controller.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { vm.process(f.absolutePath, deleteSource = true) }
                        override fun onError(e: ImageCaptureException) { error = "Scatto non riuscito: ${e.message}" }
                    })
                }, Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Scatta") }
            }
        } else {
            EmptyState(Icons.Default.PhotoCamera, "Serve il permesso della fotocamera", "La foto resta sul telefono: il testo viene riconosciuto sul dispositivo, senza inviare nulla online. In alternativa scegli una foto dalla galleria.") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Consenti la fotocamera") }
                    OutlinedButton(onClick = { gallery.launch("image/*") }) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Scegli dalla galleria") }
                }
            }
        }
    }
}

@Composable
private fun ReviewView(s: ScanStage.Review, vm: ScanViewModel) {
    val f by vm.form.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val d = s.result.data
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Controlla i dati riconosciuti", style = MaterialTheme.typography.titleLarge)
        Text("Nessun movimento è stato ancora creato: correggi i campi e conferma.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FileImage(java.io.File(ctx.filesDir, "documents/${s.result.document.fileName}").absolutePath, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!f.isIncome, { vm.edit { it.copy(isIncome = false) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Uscita") }
            SegmentedButton(f.isIncome, { vm.edit { it.copy(isIncome = true) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Entrata") }
        }
        AmountField(f.amountText, { v -> vm.edit { it.copy(amountText = v) } }, "Importo totale" + if (d.total == null) " (non riconosciuto)" else "", Modifier.fillMaxWidth())
        OutlinedTextField(f.description, { v -> vm.edit { it.copy(description = v) } }, Modifier.fillMaxWidth(), label = { Text("Descrizione / esercente" + if (d.merchant == null) " (non riconosciuto)" else "") })
        DateField(f.date, { v -> vm.edit { it.copy(date = v) } }, "Data" + if (d.date == null) " (non riconosciuta)" else "", Modifier.fillMaxWidth())
        DropdownField("Conto", data.accounts.firstOrNull { it.id == f.accountId }, data.accounts.filter { !it.archived }, { it.name }, { a -> vm.edit { it.copy(accountId = a.id) } })
        DropdownField("Categoria", data.categories.firstOrNull { it.id == f.categoryId }, CategoryTree.ordered(data.categories), { CategoryTree.label(it, data.categoriesById) }, { c -> vm.edit { it.copy(categoryId = c.id) } }, nullLabel = "Automatica (regole)", onNull = { vm.edit { it.copy(categoryId = null) } })
        OutlinedTextField(f.notes, { v -> vm.edit { it.copy(notes = v) } }, Modifier.fillMaxWidth(), label = { Text("Note (IVA e voci riconosciute)") }, minLines = 2)
        d.vat?.let { Text("IVA riconosciuta: ${Money.eur(it)}", style = MaterialTheme.typography.bodyMedium) }
        if (d.items.isNotEmpty()) SectionCard(title = "Voci riconosciute (${d.items.size})") {
            Text("Assegna a ogni voce la sua categoria (anche una sottocategoria): lo scontrino verrà diviso e vedrai nel dettaglio dove va la spesa. Le scelte fatte vengono ricordate e riproposte agli scontrini successivi; le voci senza scelta restano nella categoria principale.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.items.forEachIndexed { i, item ->
                var open by remember { mutableStateOf(false) }
                val chosen = f.itemCategories.getOrNull(i)?.let { data.categoriesById[it] }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.description, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        MoneyText(item.amount, colored = false, style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        AssistChip(onClick = { open = true }, label = { Text(chosen?.let { CategoryTree.label(it, data.categoriesById) } ?: "Come principale", maxLines = 1) })
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem(text = { Text("Come la categoria principale") }, onClick = { open = false; vm.edit { f0 -> f0.copy(itemCategories = f0.itemCategories.toMutableList().also { l -> if (i < l.size) l[i] = null }) } })
                            CategoryTree.ordered(data.categories).filter { cat -> cat.kind != it.spese.core.model.TxKind.TRASFERIMENTO }.forEach { c ->
                                DropdownMenuItem(text = { Text(CategoryTree.label(c, data.categoriesById)) }, onClick = { open = false; vm.edit { f0 -> f0.copy(itemCategories = f0.itemCategories.toMutableList().also { l -> if (i < l.size) l[i] = c.id }) } })
                            }
                        }
                    }
                }
            }
        }
        val cands by vm.candidates.collectAsStateWithLifecycle()
        cands.firstOrNull()?.let { c ->
            SectionCard(title = "Esiste già questo movimento") {
                Text("${c.description} · ${it.spese.core.common.Dates.fmt(c.bookingDate)} · ${Money.eur(c.amount)}", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(f.attach, { v -> vm.edit { it.copy(attach = v) } })
                    Text("  Collega lo scontrino a questo movimento (niente doppione)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        var showText by remember { mutableStateOf(false) }
        TextButton(onClick = { showText = !showText }) { Text(if (showText) "Nascondi testo estratto" else "Mostra testo estratto") }
        if (showText) Text(d.rawText.ifBlank { "(nessun testo riconosciuto)" }, style = MaterialTheme.typography.bodySmall)
        f.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = vm::confirm, Modifier.fillMaxWidth()) { Text("Salva movimento con documento") }
        OutlinedButton(onClick = vm::discard, Modifier.fillMaxWidth()) { Text("Scarta e rifai la scansione") }
    }
}
