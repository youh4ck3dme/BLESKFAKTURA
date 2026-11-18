package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.Invoice
import com.example.data.SlovakFinanceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.util.Calendar

@Composable
fun FilterChipCustom(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    testTagOverride: String? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(testTagOverride ?: "filter_chip_$label")
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
fun InvoiceItemRow(
    invoice: Invoice,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("invoice_row_${invoice.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val categoryColor = when (invoice.category) {
                    "Kancelária" -> MaterialTheme.colorScheme.primary
                    "Cestovné" -> MaterialTheme.colorScheme.tertiary
                    "Služby" -> MaterialTheme.colorScheme.secondary
                    "Technika" -> MaterialTheme.colorScheme.primary
                    "Strava" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(categoryColor.copy(alpha = 0.14f))
                ) {
                    Icon(
                        imageVector = when (invoice.category) {
                            "Kancelária" -> Icons.Default.Description
                            "Cestovné" -> Icons.Default.DirectionsCar
                            "Služby" -> Icons.Default.Build
                            "Technika" -> Icons.Default.Computer
                            "Strava" -> Icons.Default.Restaurant
                            else -> Icons.Default.Receipt
                        },
                        contentDescription = invoice.category,
                        tint = categoryColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = invoice.supplierName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = invoice.issueDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "•",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = invoice.category,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Show due-date warnings ONLY for unpaid invoices with an explicit dueDate (BF-23)
                    val dueMillis = remember(invoice.dueDate, invoice.isPaid) {
                        if (!invoice.isPaid && !invoice.dueDate.isNullOrBlank()) {
                            SlovakFinanceUtils.parseDateUtcMidnightMillis(invoice.dueDate)
                        } else {
                            null
                        }
                    }
                    if (invoice.isPaid) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Uhradené",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else if (dueMillis != null) {
                        val todayMillis = remember { SlovakFinanceUtils.todayUtcMidnightMillis() }
                        val diffDays = SlovakFinanceUtils.calendarDaysBetweenUtc(todayMillis, dueMillis)
                        if (diffDays < 0) {
                            val daysOverdue = -diffDays
                            val overdueText = when (daysOverdue) {
                                1 -> "Po splatnosti 1 deň"
                                in 2..4 -> "Po splatnosti $daysOverdue dni"
                                else -> "Po splatnosti $daysOverdue dní"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("invoice_overdue_badge_${invoice.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = overdueText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        } else if (diffDays in 0..3) {
                            val warningText = when (diffDays) {
                                0 -> "Splatnosť dnes!"
                                1 -> "Splatnosť zajtra"
                                else -> "Splatnosť o $diffDays dni"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("invoice_warning_badge_${invoice.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = warningText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = SlovakFinanceUtils.formatMoneyCents(invoice.effectiveTotalCents(), invoice.currency),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_invoice_${invoice.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Zmazať",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun InvoiceDetailDialog(
    invoice: Invoice,
    onDismiss: () -> Unit,
    onSave: (Invoice) -> Unit
) {
    var supplierName by remember { mutableStateOf(invoice.supplierName) }
    var ico by remember { mutableStateOf(invoice.ico ?: "") }
    var dic by remember { mutableStateOf(invoice.dic ?: "") }
    var totalAmountStr by remember { mutableStateOf(SlovakFinanceUtils.formatCentsForInput(invoice.effectiveTotalCents())) }
    var vatRateStr by remember { mutableStateOf((invoice.vatRate ?: 20.0).toString().replace('.', ',')) }
    var vatAmountStr by remember {
        mutableStateOf(invoice.effectiveVatCents()?.let { SlovakFinanceUtils.formatCentsForInput(it) } ?: "")
    }
    var issueDate by remember { mutableStateOf(invoice.issueDate) }
    var dueDate by remember { mutableStateOf(invoice.dueDate ?: "") }
    var isPaid by remember { mutableStateOf(invoice.isPaid) }
    var category by remember { mutableStateOf(invoice.category) }
    var description by remember { mutableStateOf(invoice.description ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    var isMenuExpanded by remember { mutableStateOf(false) }
    val categories = listOf("Kancelária", "Cestovné", "Služby", "Technika", "Strava", "Iné")

    val archivedBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, invoice.imageUrl) {
        value = withContext(Dispatchers.IO) {
            val path = invoice.imageUrl
            if (!path.isNullOrBlank() && File(path).exists()) {
                BitmapFactory.decodeFile(path)
            } else {
                null
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .testTag("invoice_detail_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        Text(
                            text = "Detail dokladu",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Zavrieť", tint = MaterialTheme.colorScheme.onSurface)
                        }
                }

                archivedBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Archivovaná fotografia dokladu",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                validationError?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail_validation_error")
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = supplierName,
                    onValueChange = { supplierName = it; validationError = null },
                    label = { Text("Dodávateľ") },
                    modifier = Modifier.fillMaxWidth().testTag("edit_supplier_input"),
                    singleLine = true,
                    colors = fieldColors
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = ico,
                            onValueChange = { ico = it },
                            label = { Text("IČO") },
                            modifier = Modifier.weight(1f).testTag("edit_ico_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = fieldColors
                        )
                        OutlinedTextField(
                            value = dic,
                            onValueChange = { dic = it },
                            label = { Text("DIČ / IČ DPH") },
                            modifier = Modifier.weight(1f).testTag("edit_dic_input"),
                            singleLine = true,
                            colors = fieldColors
                        )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = totalAmountStr,
                        onValueChange = { totalAmountStr = it; validationError = null },
                        label = { Text("Suma s DPH (€)") },
                        modifier = Modifier.weight(1.1f).testTag("edit_amount_input"),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = fieldColors
                    )
                    OutlinedTextField(
                        value = vatRateStr,
                        onValueChange = { vatRateStr = it; validationError = null },
                        label = { Text("Sadzba (%)") },
                        modifier = Modifier.weight(0.75f).testTag("edit_vat_rate_input"),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = fieldColors
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                        OutlinedTextField(
                            value = vatAmountStr,
                            onValueChange = { vatAmountStr = it; validationError = null },
                            label = { Text("Suma DPH (€)") },
                            modifier = Modifier.weight(1f).testTag("edit_vat_amount_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = fieldColors
                        )
                        TextButton(
                            onClick = {
                                val totalDec = SlovakFinanceUtils.parseSlovakDecimal(totalAmountStr)
                                val rateDec = SlovakFinanceUtils.parseSlovakDecimal(vatRateStr)
                                if (totalDec != null && totalDec > BigDecimal.ZERO && rateDec != null && rateDec >= BigDecimal.ZERO) {
                                    val tc = SlovakFinanceUtils.bigDecimalToCents(totalDec)
                                    val vc = SlovakFinanceUtils.calculateVatCentsFromGross(tc, rateDec)
                                    vatAmountStr = SlovakFinanceUtils.formatCentsForInput(vc)
                                    validationError = null
                                } else {
                                    validationError = "Pre výpočet DPH zadajte platnú sumu a sadzbu."
                                }
                            },
                            modifier = Modifier.testTag("recalculate_detail_vat_button")
                        ) {
                            Text("Prepočítať", fontWeight = FontWeight.Bold)
                        }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = issueDate,
                        onValueChange = { issueDate = it; validationError = null },
                        label = { Text("Vystavenie") },
                        modifier = Modifier.weight(1f).testTag("edit_date_input"),
                        singleLine = true,
                        colors = fieldColors
                    )
                    OutlinedTextField(
                        value = dueDate,
                        onValueChange = { dueDate = it; validationError = null },
                        label = { Text("Splatnosť") },
                        placeholder = { Text("RRRR-MM-DD") },
                        modifier = Modifier.weight(1f).testTag("edit_due_date_input"),
                        singleLine = true,
                        colors = fieldColors
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = category,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Kategória") },
                                trailingIcon = {
                                    IconButton(onClick = { isMenuExpanded = !isMenuExpanded }) {
                                        Icon(
                                            imageVector = if (isMenuExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                            contentDescription = "Menu"
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isMenuExpanded = !isMenuExpanded }
                                    .testTag("edit_category_selector"),
                                colors = fieldColors
                            )
                            DropdownMenu(
                                expanded = isMenuExpanded,
                                onDismissRequest = { isMenuExpanded = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            ) {
                                categories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat, color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            category = cat
                                            isMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Uhradené",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = isPaid,
                                onCheckedChange = { isPaid = it },
                                modifier = Modifier.testTag("edit_paid_switch")
                            )
                        }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Poznámka / Popis položky") },
                    modifier = Modifier.fillMaxWidth().testTag("edit_description_input"),
                    colors = fieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(48.dp).testTag("cancel_edit_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text("Zrušiť")
                        }

                        Button(
                            onClick = {
                                val trimmedSupplier = supplierName.trim()
                                if (trimmedSupplier.isEmpty()) {
                                    validationError = "Zadajte názov dodávateľa."
                                    return@Button
                                }
                                val parsedAmount = SlovakFinanceUtils.parseSlovakDecimal(totalAmountStr)
                                if (parsedAmount == null || parsedAmount <= BigDecimal.ZERO || parsedAmount > BigDecimal(1_000_000)) {
                                    validationError = "Neplatná celková suma (zadajte kladné číslo, napr. 45,90)."
                                    return@Button
                                }
                                val totalCents = SlovakFinanceUtils.bigDecimalToCents(parsedAmount)

                                val parsedRate = SlovakFinanceUtils.parseSlovakDecimal(vatRateStr)
                                if (parsedRate == null || parsedRate < BigDecimal.ZERO || parsedRate > BigDecimal(100)) {
                                    validationError = "Sadzba DPH musí byť v rozsahu 0 až 100 %."
                                    return@Button
                                }

                                val vatCents = if (vatAmountStr.isBlank()) {
                                    null
                                } else {
                                    val parsedVat = SlovakFinanceUtils.parseSlovakDecimal(vatAmountStr)
                                    if (parsedVat == null || parsedVat < BigDecimal.ZERO) {
                                        validationError = "Neplatný formát sumy DPH."
                                        return@Button
                                    }
                                    val vc = SlovakFinanceUtils.bigDecimalToCents(parsedVat)
                                    if (vc > totalCents) {
                                        validationError = "DPH nemôže presiahnuť celkovú sumu."
                                        return@Button
                                    }
                                    vc
                                }

                                val normIssue = SlovakFinanceUtils.normalizeIsoDate(issueDate)
                                if (normIssue == null) {
                                    validationError = "Neplatný dátum vystavenia."
                                    return@Button
                                }

                                val normDue = if (dueDate.isBlank()) {
                                    null
                                } else {
                                    SlovakFinanceUtils.normalizeIsoDate(dueDate) ?: run {
                                        validationError = "Neplatný dátum splatnosti."
                                        return@Button
                                    }
                                }

                                onSave(
                                    invoice.copy(
                                        supplierName = trimmedSupplier,
                                        ico = ico.trim().ifBlank { null },
                                        dic = dic.trim().ifBlank { null },
                                        totalAmount = SlovakFinanceUtils.centsToDouble(totalCents),
                                        totalAmountCents = totalCents,
                                        vatRate = parsedRate.toDouble(),
                                        vatAmount = vatCents?.let { SlovakFinanceUtils.centsToDouble(it) },
                                        vatAmountCents = vatCents,
                                        issueDate = normIssue,
                                        dueDate = normDue,
                                        isPaid = isPaid,
                                        category = category,
                                        description = description.trim().ifBlank { null }
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f).height(48.dp).testTag("save_edit_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Uložiť", fontWeight = FontWeight.Bold)
                        }
                    }
            }
        }
    }
}

@Composable
fun MonthlyExpensesBarChart(
    invoices: List<Invoice>,
    currentYear: Int,
    selectedMonthIdx: Int?,
    onSelectMonth: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "Máj", "Jún", "Júl", "Aug", "Sep", "Okt", "Nov", "Dec")
    val monthFullNames = listOf(
        "Január", "Február", "Marec", "Apríl", "Máj", "Jún",
        "Júl", "August", "September", "Október", "November", "December"
    )

    val monthlyCentTotals = remember(invoices, currentYear) {
        val totals = LongArray(12) { 0L }
        invoices.forEach { invoice ->
            val ym = SlovakFinanceUtils.extractYearMonth(invoice.issueDate)
            if (ym != null && ym.first == currentYear && ym.second in 1..12) {
                totals[ym.second - 1] += invoice.effectiveTotalCents()
            }
        }
        totals
    }

    val maxTotalCents = remember(monthlyCentTotals) {
        val max = monthlyCentTotals.maxOrNull() ?: 0L
        if (max == 0L) 10_000L else max
    }

    val currentMonthIdx = remember { Calendar.getInstance().get(Calendar.MONTH) }
    val dispIdx = selectedMonthIdx ?: currentMonthIdx
    val dispMonthName = if (selectedMonthIdx != null) "${monthFullNames[dispIdx]} $currentYear (filter)" else "${monthFullNames[dispIdx]} $currentYear"
    val dispCents = monthlyCentTotals[dispIdx]

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("monthly_expenses_chart_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Leaderboard,
                        contentDescription = "Graf",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MESAČNÝ PREHĽAD VÝDAVKOV",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (selectedMonthIdx != null) {
                    TextButton(
                        onClick = { onSelectMonth(null) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("clear_month_filter_button")
                    ) {
                        Text(
                            text = "Zrušiť filter",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "ROK $currentYear",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = dispMonthName,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = SlovakFinanceUtils.formatMoneyCents(dispCents),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = if (selectedMonthIdx != null) "Kliknite znova pre všetky" else "Ťuknutím filtrujte mesiac",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(4) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    monthlyCentTotals.forEachIndexed { idx, totalCents ->
                        val isSelected = selectedMonthIdx == idx
                        val barHeightFraction = (totalCents.toFloat() / maxTotalCents.toFloat()).coerceIn(0.03f, 1.0f)

                        val animatedFraction by animateFloatAsState(
                            targetValue = barHeightFraction,
                            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                            label = "BarHeightAnimation"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    onSelectMonth(if (isSelected) null else idx)
                                }
                                .padding(horizontal = 2.dp)
                                .testTag("chart_month_bar_$idx")
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp * animatedFraction)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = if (isSelected) {
                                                listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.secondary
                                                )
                                            } else if (totalCents > 0L) {
                                                listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                )
                                            } else {
                                                listOf(
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                                )
                                            }
                                        )
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                    )
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = monthNames[idx],
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
