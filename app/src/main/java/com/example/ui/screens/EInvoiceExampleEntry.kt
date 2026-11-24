package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@Composable
internal fun EInvoiceExampleEntry() {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("eFaktúra 2027 · Pozrieť vzor")
    }
    if (!open) return

    val context = LocalContext.current
    val xml = remember(context) {
        context.assets.open("efaktura/base-example.xml").bufferedReader().use { it.readText() }
    }
    val fields = remember(xml) { summaryFields(xml) }
    var showXml by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Vzor eFaktúry") },
        text = {
            if (showXml) {
                Text(xml, Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Medzinárodný príklad OpenPeppol · BIS Billing 3.0")
                    for ((label, value) in fields) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Text(value)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { showXml = !showXml }) {
                Text(if (showXml) "Zobraziť prehľad" else "Zobraziť XML")
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Zavrieť") } }
    )
}

// Only reads the fixed, bundled OpenPeppol sample.
private fun summaryFields(xml: String): List<Pair<String, String>> {
    val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream()).documentElement
    fun Element.value(name: String): String =
        getElementsByTagNameNS("*", name).item(0).textContent
    fun party(name: String): String =
        (root.getElementsByTagNameNS("*", name).item(0) as Element).value("RegistrationName")
    val currency = root.value("DocumentCurrencyCode")
    return listOf(
        "Číslo faktúry" to root.value("ID"),
        "Vystavená" to root.value("IssueDate"),
        "Splatnosť" to root.value("DueDate"),
        "Dodávateľ" to party("AccountingSupplierParty"),
        "Odberateľ" to party("AccountingCustomerParty"),
        "Základ dane" to "${root.value("TaxExclusiveAmount")} $currency",
        "DPH" to "${root.value("TaxAmount")} $currency",
        "Na úhradu" to "${root.value("PayableAmount")} $currency"
    )
}
