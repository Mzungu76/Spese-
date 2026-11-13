package it.spese.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.core.designsystem.theme.MoneyStyle
import it.spese.core.designsystem.theme.SpeseTheme
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun Long.toColor(): Color = Color(this.toInt())

/** Importo colorato: verde positivo, rosso negativo; `muted` per i giroconti. */
@Composable
fun MoneyText(
    amount: BigDecimal, modifier: Modifier = Modifier, signed: Boolean = false, decimals: Boolean = true, muted: Boolean = false,
    colored: Boolean = true, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge, fontWeight: FontWeight? = FontWeight.Medium,
) {
    val fin = SpeseTheme.finance
    val color = when {
        muted -> fin.neutral
        !colored -> MaterialTheme.colorScheme.onSurface
        amount.signum() < 0 -> fin.negative
        amount.signum() > 0 -> fin.positive
        else -> MaterialTheme.colorScheme.onSurface
    }
    val text = if (decimals) (if (signed) Money.eurSigned(amount) else Money.eur(amount)) else Money.eur0(amount).let { if (signed && amount.signum() > 0) "+$it" else it }
    Text(text, modifier, color = color, style = style.merge(MoneyStyle), fontWeight = fontWeight, maxLines = 1)
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
fun KpiCard(label: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface, hint: String? = null, value: @Composable () -> Unit = {}) {
    Card(modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            value()
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CategoryAvatar(name: String, color: Color, modifier: Modifier = Modifier, size: Int = 40) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null, selected: Boolean = false, onLongClick: (() -> Unit)? = null,
) {
    val click = if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier
    Row(
        modifier.fillMaxWidth().then(click)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Campo importo che accetta la virgola decimale italiana. `value` è il testo digitato. */
@Composable
fun AmountField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, supporting: String? = null, isError: Boolean = false) {
    OutlinedTextField(
        value, { s -> if (s.all { it.isDigit() || it in ",.-" }) onValueChange(s) }, modifier = modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), supportingText = supporting?.let { { Text(it) } }, isError = isError, suffix = { Text("€") },
    )
}

fun parseAmountOrNull(s: String): BigDecimal? = NumberParser.parseMoney(s)

/** Campo data con selettore Material 3. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: LocalDate?, onValueChange: (LocalDate) -> Unit, label: String, modifier: Modifier = Modifier, clearable: Boolean = false, onClear: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value?.let { Dates.fmt(it) } ?: "", onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = modifier.clickable { open = true },
        enabled = false, colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant, disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
        trailingIcon = {
            if (clearable && value != null && onClear != null) IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "Cancella data") }
            else Icon(Icons.Default.CalendarMonth, "Scegli data")
        },
    )
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (value ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { onValueChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text("Conferma") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annulla") } }) { DatePicker(st) }
    }
}

/** Menu a tendina semplice (esposto). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(label: String, selected: T?, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, nullLabel: String? = null, onNull: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }, modifier) {
        OutlinedTextField(
            value = selected?.let(text) ?: nullLabel ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            if (nullLabel != null && onNull != null) DropdownMenuItem(text = { Text(nullLabel) }, onClick = { onNull(); expanded = false })
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); expanded = false }) }
        }
    }
}

@Composable
fun ColorDot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    Row(modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SeverityDot(level: Int) {
    val c = when (level) { 2 -> MaterialTheme.colorScheme.error; 1 -> SpeseTheme.finance.warning; else -> MaterialTheme.colorScheme.primary }
    Box(Modifier.size(10.dp).clip(CircleShape).background(c).semantics { contentDescription = when (level) { 2 -> "Critico"; 1 -> "Attenzione"; else -> "Informazione" } })
}

@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier, over: Boolean = false) {
    val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChipChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(selected == v, { onSelect(v) }, { Text(label) }) }
    }
}


/** Etichetta "sopra-titolo": piccola, grigia: serve a creare gerarchia rispetto al numero che la segue. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Intestazione di sezione fuori dalle carte (stile elenco di una banca digitale). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Pulsante d'azione rapida tondo con etichetta sotto (Aggiungi, Scansiona…). */
@Composable
fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Variazione percentuale con freccia e colore (verde = bene). `upIsGood` false per le spese. */
@Composable
fun DeltaBadge(percent: BigDecimal?, upIsGood: Boolean, modifier: Modifier = Modifier) {
    if (percent == null) return
    val fin = SpeseTheme.finance
    val up = percent.signum() > 0
    val good = if (percent.signum() == 0) null else (up == upIsGood)
    val color = when (good) { true -> fin.positive; false -> fin.negative; null -> fin.neutral }
    Text((if (up) "▲ " else if (percent.signum() < 0) "▼ " else "") + percent.abs().setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + "%",
        modifier, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** Selettore a pillola (stile app bancarie): una sola riga, voci sempre intere. */
@Composable
fun <T> PillSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { onSelect(v) }, contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
