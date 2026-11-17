package it.spese.app.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Campo di ricerca: il testo digitato vive qui (nessuna battuta persa per elaborazioni in corso) e arriva al filtro con un breve ritardo,
 * così la lista non si ricalcola a ogni lettera. Un valore esterno vuoto (filtri reimpostati) svuota il campo.
 */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, debounceMs: Long = 180, autoFocus: Boolean = false) {
    var text by remember { mutableStateOf(value) }
    var lastSent by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value.isEmpty() && text.isNotEmpty() && lastSent.isNotEmpty()) { text = ""; lastSent = "" }
        else if (value.isNotEmpty() && text.isEmpty()) { text = value; lastSent = value }
    }
    LaunchedEffect(text) { if (text != lastSent) { delay(debounceMs); lastSent = text; onValueChange(text) } }
    val focus = remember { FocusRequester() }
    val manager = LocalFocusManager.current
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(text, { text = it }, modifier.fillMaxWidth().focusRequester(focus).testTag("search_field"), singleLine = true, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; lastSent = ""; onValueChange("") }) { Icon(Icons.Default.Clear, "Cancella ricerca") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { manager.clearFocus() }),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
}
