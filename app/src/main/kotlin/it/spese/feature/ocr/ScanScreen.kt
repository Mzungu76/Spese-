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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.core.common.Money
import it.spese.domain.usecase.ReceiptDedupe
import it.spese.domain.usecase.ScanResult
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
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uris.forEach { vm.addPage(it.toString()) } }
    val pages by vm.pages.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (granted) {
            val controller = remember { LifecycleCameraController(ctx).apply { setEnabledUseCases(CameraController.IMAGE_CAPTURE) } }
            LaunchedEffect(controller) { controller.bindToLifecycle(owner) }
            DisposableEffect(controller) { onDispose { controller.unbind() } }
            var torch by remember { mutableStateOf(false) }
            val tilt = rememberTiltDegrees()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView({ c -> PreviewView(c).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FIT_CENTER } }, Modifier.fillMaxSize())
                // guida: cornice verticale in cui far stare lo scontrino, tenendo il telefono parallelo al tavolo
                val guide = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f)
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width * 0.58f; val h = size.height * 0.92f
                    val l = (size.width - w) / 2f; val t = (size.height - h) / 2f; val k = 28.dp.toPx()
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx())
                    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(guide, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + dx * k, y), stroke.width)
                        drawLine(guide, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, y + dy * k), stroke.width)
                    }
                    corner(l, t, 1f, 1f); corner(l + w, t, -1f, 1f); corner(l, t + h, 1f, -1f); corner(l + w, t + h, -1f, -1f)
                }
                IconButton(onClick = { torch = !torch; controller.enableTorch(torch) }, Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, if (torch) "Spegni la torcia" else "Accendi la torcia", tint = guide)
                }
                tilt?.let { t ->
                    val ok = t <= 12f
                    Text(if (ok) "Telefono dritto ✓" else "Inclinato di ${t.toInt()}°: tienilo parallelo al tavolo",
                        Modifier.align(Alignment.BottomCenter).padding(8.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = if (ok) androidx.compose.ui.graphics.Color(0xFF7BE08A) else androidx.compose.ui.graphics.Color(0xFFFFC857))
                }
            }
            PagesStrip(pages, vm)
            Text(if (pages.isEmpty()) "Inquadra lo scontrino: piano e ben illuminato. Se è lungo, scatta più foto dall'alto verso il basso facendole sovrapporre di qualche riga: le righe doppie le tolgo io."
                else "Foto ${pages.size}. Scatta la successiva ripartendo da qualche riga prima della fine dell'ultima, oppure leggi lo scontrino.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { gallery.launch("image/*") }, Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Galleria") }
                Button(onClick = {
                    val f = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                    controller.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { vm.addPage(f.absolutePath) }
                        override fun onError(e: ImageCaptureException) { error = "Scatto non riuscito: ${e.message}" }
                    })
                }, Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(if (pages.isEmpty()) "Scatta" else "Altra foto") }
            }
            if (pages.isNotEmpty()) Button(onClick = vm::readPages, Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text(if (pages.size == 1) "Leggi lo scontrino" else "Leggi lo scontrino (${pages.size} foto)")
            }
        } else {
            EmptyState(Icons.Default.PhotoCamera, "Serve il permesso della fotocamera", "La foto resta sul telefono: il testo viene riconosciuto sul dispositivo, senza inviare nulla online. In alternativa scegli una foto dalla galleria.") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Consenti la fotocamera") }
                    OutlinedButton(onClick = { gallery.launch("image/*") }) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Scegli dalla galleria (anche più foto)") }
                    PagesStrip(pages, vm)
                    if (pages.isNotEmpty()) Button(onClick = vm::readPages) { Text(if (pages.size == 1) "Leggi lo scontrino" else "Leggi lo scontrino (${pages.size} foto)") }
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
        FileImage(java.io.File(ctx.filesDir, "documents/${s.result.document.fileName}").absolutePath, Modifier.fillMaxWidth().height(if (s.result.pages > 1) 240.dp else 180.dp).clip(RoundedCornerShape(12.dp)), maxPx = 1600, contentScale = if (s.result.pages > 1) androidx.compose.ui.layout.ContentScale.FillWidth else androidx.compose.ui.layout.ContentScale.Crop)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!f.isIncome, { vm.edit { it.copy(isIncome = false) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Uscita") }
            SegmentedButton(f.isIncome, { vm.edit { it.copy(isIncome = true) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Entrata") }
        }
        AmountField(f.amountText, { v -> vm.edit { it.copy(amountText = v) } }, "Importo totale" + if (d.total == null) " (non riconosciuto)" else "", Modifier.fillMaxWidth())
        OutlinedTextField(f.description, { v -> vm.edit { it.copy(description = v) } }, Modifier.fillMaxWidth(), label = { Text("Descrizione / esercente" + if (d.merchant == null) " (non riconosciuto)" else "") })
        DateField(f.date, { v -> vm.edit { it.copy(date = v) } }, "Data" + if (d.date == null) " (non riconosciuta)" else "", Modifier.fillMaxWidth())
        DropdownField("Conto", data.accounts.firstOrNull { it.id == f.accountId }, data.accounts.filter { !it.archived }, { it.name }, { a -> vm.edit { it.copy(accountId = a.id) } })
        DropdownField("Categoria", data.categories.firstOrNull { it.id == f.categoryId }, CategoryTree.ordered(data.categories), { CategoryTree.label(it, data.categoriesById) }, { c -> vm.edit { it.copy(categoryId = c.id, newParentId = null) } }, nullLabel = "Automatica (regole)", onNull = { vm.edit { it.copy(categoryId = null) } })
        OutlinedTextField(f.notes, { v -> vm.edit { it.copy(notes = v) } }, Modifier.fillMaxWidth(), label = { Text("Note (IVA e voci riconosciute)") }, minLines = 2)
        d.vat?.let { Text("IVA riconosciuta: ${Money.eur(it)}", style = MaterialTheme.typography.bodyMedium) }
        ReceiptChecks(s.result, f, vm)
        if (d.items.isNotEmpty()) SectionCard(title = "Voci riconosciute (${d.items.size})") {
            Text("Assegna a ogni voce la sua categoria (anche una sottocategoria): lo scontrino verrà diviso e vedrai nel dettaglio dove va la spesa. Le scelte fatte vengono ricordate e riproposte agli scontrini successivi; le voci senza scelta restano nella categoria principale.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.items.forEachIndexed { i, item ->
                var open by remember { mutableStateOf(false) }
                val chosen = f.itemCategories.getOrNull(i)?.let { data.categoriesById[it] }
                val proposed = f.itemNew.getOrNull(i)?.takeIf { chosen == null && it in f.createNames }
                val out = i in f.excluded
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.description + if (item.quantity > 1) "  ×${item.quantity}" else "", maxLines = 1, style = MaterialTheme.typography.bodyMedium.let { st -> if (out) st.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant) else st })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MoneyText(item.amount, colored = false, style = MaterialTheme.typography.bodySmall)
                            if (i in d.weakItems && !out) Text("⚠ controlla", style = MaterialTheme.typography.labelSmall, color = SpeseTheme.finance.warning)
                        }
                    }
                    if (!out) Box {
                        AssistChip(onClick = { open = true }, label = { Text(chosen?.let { CategoryTree.label(it, data.categoriesById) } ?: proposed?.let { "＋ $it" } ?: "Come principale", maxLines = 1) })
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem(text = { Text("Come la categoria principale") }, onClick = { open = false; vm.edit { f0 -> f0.copy(itemCategories = f0.itemCategories.toMutableList().also { l -> if (i < l.size) l[i] = null }, itemNew = f0.itemNew.toMutableList().also { l -> if (i < l.size) l[i] = null }) } })
                            CategoryTree.ordered(data.categories).filter { cat -> cat.kind != it.spese.core.model.TxKind.TRASFERIMENTO }.forEach { c ->
                                DropdownMenuItem(text = { Text(CategoryTree.label(c, data.categoriesById)) }, onClick = { open = false; vm.edit { f0 -> f0.copy(itemCategories = f0.itemCategories.toMutableList().also { l -> if (i < l.size) l[i] = c.id }) } })
                            }
                        }
                    }
                    IconButton(onClick = { vm.edit { f0 -> f0.copy(excluded = if (out) f0.excluded - i else f0.excluded + i) } }, Modifier.size(36.dp)) {
                        Icon(if (out) Icons.Default.Restore else Icons.Default.Close, if (out) "Rimetti la voce" else "Togli la voce: non fa parte dello scontrino", Modifier.size(18.dp))
                    }
                }
            }
        }
        val newNames = f.itemNew.filterNotNull().groupingBy { it }.eachCount()
        if (newNames.isNotEmpty()) {
            val parent = vm.parentFor(f)
            SectionCard(title = "Sottocategorie da creare") {
                val tops = data.categories.filter { c -> c.active && c.kind == it.spese.core.model.TxKind.SPESA && c.systemKey == null && c.parentId == null }
                DropdownField("Crea in", parent, tops, { c -> c.name }, { c -> vm.edit { f0 -> f0.copy(newParentId = c.id) } })
                if (parent == null) Text("Scegli la categoria principale (es. Supermercato) in cui creare: ${newNames.keys.joinToString(", ")}.", style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("Propongo queste sottocategorie in ${parent.name}, in base ai prodotti riconosciuti. Si creano solo se salvi lo scontrino; togli la spunta a quelle che non vuoi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    newNames.forEach { (n, count) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(n in f.createNames, { on -> vm.edit { f0 -> f0.copy(createNames = if (on) f0.createNames + n else f0.createNames - n) } })
                            Text("$n · $count ${if (count == 1) "voce" else "voci"}", style = MaterialTheme.typography.bodyMedium)
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
        if (showText) {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Text(d.rawText.ifBlank { "(nessun testo riconosciuto)" }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(d.rawText)) }) { Text("Copia il testo letto") }
        }
        f.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = vm::confirm, Modifier.fillMaxWidth()) { Text("Salva movimento con documento") }
        OutlinedButton(onClick = vm::discard, Modifier.fillMaxWidth()) { Text("Scarta e rifai la scansione") }
    }
}

/** Miniature delle foto aggiunte, in ordine dall'alto verso il basso dello scontrino, con sposta ed elimina. */
@Composable
private fun PagesStrip(pages: List<String>, vm: ScanViewModel) {
    if (pages.isEmpty()) return
    val blurry by vm.blurry.collectAsStateWithLifecycle()
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(pages, key = { _, p -> p }) { i, p ->
            Card(Modifier.width(92.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FileImage(p, Modifier.fillMaxWidth().height(96.dp), maxPx = 300)
                    Text(if (p in blurry) "Foto ${i + 1} ⚠ sfocata" else "Foto ${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (p in blurry) SpeseTheme.finance.warning else MaterialTheme.colorScheme.onSurface)
                    Row {
                        IconButton(onClick = { vm.movePage(i, -1) }, Modifier.size(28.dp), enabled = i > 0) { Icon(Icons.Default.ArrowBack, "Sposta prima", Modifier.size(16.dp)) }
                        IconButton(onClick = { vm.removePage(i) }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Elimina la foto ${i + 1}", Modifier.size(16.dp)) }
                        IconButton(onClick = { vm.movePage(i, 1) }, Modifier.size(28.dp), enabled = i < pages.size - 1) { Icon(Icons.Default.ArrowForward, "Sposta dopo", Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}

/** Esito dell'unione delle foto e quadratura delle voci col totale, con le correzioni a un tocco. */
@Composable
private fun ReceiptChecks(r: ScanResult, f: ScanForm, vm: ScanViewModel) {
    val d = r.data
    val joins = r.overlaps.mapIndexed { i, n -> if (n > 0) null else "Tra la foto ${i + 1} e la ${i + 2} non ho trovato righe in comune: controlla che non manchino voci." }.filterNotNull()
    val included = d.items.filterIndexed { i, _ -> i !in f.excluded }
    val sum = included.fold(java.math.BigDecimal.ZERO) { a, b -> a + b.amount }
    val amount = it.spese.core.common.NumberParser.parseMoney(f.amountText)?.abs()
    val gap = if (amount == null || included.isEmpty()) null else amount - sum
    val repeated = remember(d) { ReceiptDedupe.repeatedBlocks(d.items) }
    if (r.pages <= 1 && gap == null && f.note == null && d.repairs.isEmpty() && d.weakItems.isEmpty()) return
    SectionCard(title = "Controllo della lettura") {
        f.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (d.repairs.isNotEmpty()) Text("Ho corretto ${d.repairs.size} ${if (d.repairs.size == 1) "prezzo letto male" else "prezzi letti male"} perché la somma non tornava: " +
            d.repairs.joinToString("; ") { "${it.description} ${Money.eur(it.from)} → ${Money.eur(it.to)} (${it.reason})" } + ".", style = MaterialTheme.typography.bodyMedium)
        if (d.weakItems.isNotEmpty()) Text("${d.weakItems.size} ${if (d.weakItems.size == 1) "voce letta" else "voci lette"} con poca sicurezza (⚠): controllale con la foto.", style = MaterialTheme.typography.bodySmall, color = SpeseTheme.finance.warning)
        if (r.pages > 1) Text(if (joins.isEmpty()) "${r.pages} foto unite: le righe doppie nelle sovrapposizioni sono state tolte." else "${r.pages} foto unite.", style = MaterialTheme.typography.bodyMedium)
        joins.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = SpeseTheme.finance.warning) }
        if (gap != null) {
            val ok = gap.abs() < java.math.BigDecimal("0.02")
            if (ok) Text("Le ${included.size} voci sommano ${Money.eur(sum)}: torna con il totale.", style = MaterialTheme.typography.bodyMedium, color = SpeseTheme.finance.positive)
            else {
                Text("Le voci sommano ${Money.eur(sum)}, il totale è ${Money.eur(amount!!)}: differenza ${Money.eur(gap.abs())}." +
                    if (gap.signum() < 0) " Probabile voce doppia o prezzo letto male." else " Probabile riga mancante o non letta.", style = MaterialTheme.typography.bodyMedium, color = SpeseTheme.finance.warning)
                Text(if (gap.signum() < 0) "Puoi salvare comunque: ripartisco il totale in proporzione tra le categorie scelte. Per un risultato esatto togli le voci sbagliate (la ×) o usa i pulsanti qui sotto."
                    else "Puoi salvare comunque: la differenza resta nella categoria principale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val extra = repeated.filter { it !in f.excluded }
                    if (extra.isNotEmpty()) AssistChip(onClick = { vm.edit { f0 -> f0.copy(excluded = f0.excluded + extra) } }, label = { Text("Togli ${extra.size} voci ripetute") })
                    AssistChip(onClick = { vm.edit { f0 -> f0.copy(amountText = sum.toPlainString().replace('.', ',')) } }, label = { Text("Totale = somma voci (${Money.eur(sum)})") })
                }
            }
        }
    }
}

/** Inclinazione del telefono rispetto al piano orizzontale (gradi), dall'accelerometro: per fotografare dall'alto, parallelo al tavolo. null se manca il sensore. */
@Composable
private fun rememberTiltDegrees(): Float? {
    val ctx = LocalContext.current
    var tilt by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val g = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()).coerceAtLeast(0.1)
                val a = Math.toDegrees(Math.acos((Math.abs(e.values[2]) / g).coerceIn(0.0, 1.0))).toFloat()
                tilt = tilt?.let { it * 0.8f + a * 0.2f } ?: a
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}
