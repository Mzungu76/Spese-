package it.spese.feature.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.designsystem.component.*
import it.spese.domain.widgets.LayoutOps
import it.spese.domain.widgets.LayoutPresets
import it.spese.domain.widgets.WidgetCatalog
import it.spese.domain.widgets.WidgetGroup
import it.spese.domain.widgets.WidgetInstance
import it.spese.domain.widgets.WidgetPage

/** «Personalizza la Home»: scegli un setup pronto, riordina, nascondi o aggiungi i widget. */
@Composable
fun CustomizeScreen(onBack: () -> Unit, vm: CustomizeViewModel = hiltViewModel()) {
    val layout by vm.layout.collectAsStateWithLifecycle()
    val customized by vm.customized.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val addable = LayoutOps.available(page, layout)
    Column(Modifier.fillMaxSize()) {
        BackHeader("Personalizza", onBack)
        Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) { ChipChoice(WidgetPage.entries.map { p -> p to p.label }, page, vm::setPage, Modifier.testTag("customize_pages")) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SectionHeader("Setup pronti", Modifier.padding(horizontal = 24.dp)) }
            item {
                Text("«Qui» lo applica a ${page.label}, «Ovunque» a tutte le pagine; poi puoi ritoccare l'ordine.", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(LayoutPresets.all, key = { p -> "preset-${p.id}" }) { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { vm.applyPreset(p.id) }, Modifier.testTag("preset_${p.id}")) { Text("Qui") }
                    OutlinedButton(onClick = { vm.applyPresetEverywhere(p.id) }, Modifier.testTag("preset_all_${p.id}")) { Text("Ovunque") }
                }
            }
            if (customized) item {
                TextButton(onClick = vm::reset, Modifier.padding(horizontal = 12.dp).testTag("widgets_reset")) { Text("Ripristina la composizione predefinita") }
            }
            item { SectionHeader("I widget di ${page.label}", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            items(layout, key = { w -> "w-${w.uid}" }) { w -> WidgetRow(w, layout.firstOrNull()?.uid == w.uid, layout.lastOrNull()?.uid == w.uid, vm) }
            if (addable.isNotEmpty()) {
                item { SectionHeader("Aggiungi", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                WidgetGroup.entries.forEach { g ->
                    val inGroup = addable.filter { s -> s.group == g }
                    if (inGroup.isNotEmpty()) {
                        item(key = "g-${g.name}") { Text(g.label, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(inGroup, key = { s -> "add-${s.id}" }) { s ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.add(s.id) }, Modifier.testTag("add_${s.id}")) { Icon(Icons.Default.Add, "Aggiungi ${s.title}") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(w: WidgetInstance, first: Boolean, last: Boolean, vm: CustomizeViewModel) {
    val spec = WidgetCatalog.spec(w.widgetId) ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Switch(w.enabled, { on -> vm.setEnabled(w.uid, on) }, Modifier.testTag("toggle_${w.widgetId}"), enabled = !spec.required)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.bodyLarge, color = if (w.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!w.enabled) Text("Nascosto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.move(w.uid, -1) }, enabled = !first) { Icon(Icons.Default.ArrowUpward, "Sposta su") }
            IconButton(onClick = { vm.move(w.uid, 1) }, enabled = !last) { Icon(Icons.Default.ArrowDownward, "Sposta giù") }
            if (!spec.required) IconButton(onClick = { vm.remove(w.uid) }) { Icon(Icons.Default.Close, "Rimuovi") }
        }
        if (w.widgetId == WidgetCatalog.SECTION) {
            OutlinedTextField(w.options["title"].orEmpty(), { t -> vm.setOption(w.uid, "title", t) }, label = { Text("Titolo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (w.widgetId == "categories") {
            ChipChoice(listOf("SPESA" to "Parte dalle spese", "ENTRATA" to "Parte dalle entrate"), w.options["kind"] ?: "SPESA", { k -> vm.setOption(w.uid, "kind", k) })
        }
    }
}
