package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Invoice
import com.example.data.SlovakFinanceUtils
import com.example.ui.InvoiceViewModel
import com.example.ui.PdfExportResult
import com.example.ui.PdfExporter
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: InvoiceViewModel,
    onNavigateToScan: () -> Unit,
    onNavigateToPricing: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val invoices by viewModel.invoices.collectAsState()
    val userStats by viewModel.userStats.collectAsState()
    val syncError by viewModel.syncError.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("Všetko") }
    var selectedMonthIdx by rememberSaveable { mutableStateOf<Int?>(null) }
    var activeNavTab by rememberSaveable { mutableStateOf("HOME") }
    var selectedInvoiceForDetail by remember { mutableStateOf<Invoice?>(null) }
    var isSavingInvoiceDetail by remember { mutableStateOf(false) }
    var isExportingPdf by remember { mutableStateOf(false) }

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val monthFullNames = remember {
        listOf(
            "Január", "Február", "Marec", "Apríl", "Máj", "Jún",
            "Júl", "August", "September", "Október", "November", "December"
        )
    }
    val slovakCategories = remember {
        listOf("Všetko", "Kancelária", "Cestovné", "Služby", "Technika", "Strava", "Iné")
    }

    val filteredInvoices = remember(invoices, searchQuery, selectedCategory, selectedMonthIdx, currentYear) {
        invoices.filter { invoice ->
            val matchesSearch = invoice.supplierName.contains(searchQuery, ignoreCase = true) ||
                (invoice.description?.contains(searchQuery, ignoreCase = true) ?: false)
            val matchesCategory = selectedCategory == "Všetko" || invoice.category == selectedCategory
            val matchesMonth = if (selectedMonthIdx == null) {
                true
            } else {
                val ym = SlovakFinanceUtils.extractYearMonth(invoice.issueDate)
                ym != null && ym.first == currentYear && ym.second == (selectedMonthIdx!! + 1)
            }
            matchesSearch && matchesCategory && matchesMonth
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Blesková Faktúra",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${currentUser?.displayName ?: "Používateľ"} • Evidencia $currentYear",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToPricing,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("pricing_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = if (viewModel.isInternalTesting) "O internej verzii" else "Predplatné",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Odhlásiť sa",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.border(width = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            ) {
                NavigationBarItem(
                    selected = activeNavTab == "HOME",
                    onClick = {
                        activeNavTab = "HOME"
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Domov") },
                    label = { Text("Domov", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = activeNavTab == "DOCS",
                    onClick = {
                        activeNavTab = "DOCS"
                        searchQuery = ""
                        selectedCategory = "Všetko"
                        selectedMonthIdx = null
                        scope.launch { listState.animateScrollToItem(5) }
                    },
                    icon = { Icon(Icons.Default.Receipt, contentDescription = "Doklady") },
                    label = { Text("Doklady") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToPricing,
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = if (viewModel.isInternalTesting) "Interný test" else "Predplatné") },
                    label = { Text(if (viewModel.isInternalTesting) "Interný test" else "Predplatné") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToSettings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Nastavenia") },
                    label = { Text("Nastavenia") },
                    modifier = Modifier.testTag("nav_settings_tab")
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    viewModel.shouldAutoLaunchCamera = true
                    onNavigateToScan()
                },
                modifier = Modifier
                    .padding(8.dp)
                    .testTag("fab_scan_invoice"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = "Skenovať"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Bleskový Sken",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
            }

            syncError?.let { errMsg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_error_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = errMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearSyncError() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Zavrieť",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // Bento Cell 1: Hero Section
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hero_expenses_bento_card"),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "VÝDAVKY S DPH",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (viewModel.isInternalTesting) "Interný test" else if (userStats.isSubscribed) "Blesk Premium" else "${userStats.credits} kreditov",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = SlovakFinanceUtils.formatMoneyCents(userStats.effectiveTotalInvoicedCents()),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 36.sp,
                                letterSpacing = (-1).sp
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Zaevidované doklady: ${userStats.invoiceCount} ks",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Bento Cell 2 & 3: Quick Actions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        onClick = onNavigateToScan,
                        modifier = Modifier
                            .weight(1f)
                            .height(130.dp)
                            .testTag("action_scan_bento_card"),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlashOn,
                                    contentDescription = "Skenovať",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = if (viewModel.isInternalTesting) "Prečítať doklad" else "Skenuj AI",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Pridať účtenku",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }

                    Card(
                        onClick = onNavigateToPricing,
                        modifier = Modifier
                            .weight(1f)
                            .height(130.dp)
                            .testTag("action_pricing_bento_card"),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onTertiaryContainer)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = if (viewModel.isInternalTesting) "O internej verzii" else "Predplatné",
                                    tint = MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = if (viewModel.isInternalTesting) "Interné testovanie" else "Predplatné",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = if (viewModel.isInternalTesting) "Lokálne OCR bez nákupov" else "AI Kredity & Balíky",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
            }

            // Bento Cell 4: Scanner Quota Card (BF-33)
            item {
                val creditCapacity = maxOf(10, userStats.credits)
                val creditPercent = if (viewModel.isInternalTesting || userStats.isSubscribed) 100 else (userStats.credits * 100) / creditCapacity

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("status_bento_card"),
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
                            Text(
                                text = if (viewModel.isInternalTesting) "ČÍTANIE DOKLADOV" else "STAV AI SKENERA",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (viewModel.isInternalTesting) "LOKÁLNE OCR" else "AI AKTÍVNE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (viewModel.isInternalTesting) {
                                    "Interný test bez kreditov"
                                } else if (userStats.isSubscribed) {
                                    "Blesk Premium (Neobmedzene)"
                                } else {
                                    "Kredity: ${userStats.credits} skenov zostáva"
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "$creditPercent%",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = {
                                if (viewModel.isInternalTesting || userStats.isSubscribed) 1.0f
                                else (userStats.credits.toFloat() / creditCapacity.toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Overené",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Údaje a DPH pred uložením vždy skontrolujte",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Bento Cell 5: Interactive Monthly Expenses Bar Chart
            item {
                MonthlyExpensesBarChart(
                    invoices = invoices,
                    currentYear = currentYear,
                    selectedMonthIdx = selectedMonthIdx,
                    onSelectMonth = { selectedMonthIdx = it }
                )
            }

            // List Header + PDF Export
            item {
                val periodLabel = selectedMonthIdx?.let { "${monthFullNames[it]} $currentYear" } ?: "Všetky obdobia"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedMonthIdx != null) {
                            "DOKLADY (${monthFullNames[selectedMonthIdx!!].uppercase()})"
                        } else {
                            "ZOZNAM DOKLADOV"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    TextButton(
                        enabled = !isExportingPdf,
                        onClick = {
                            if (filteredInvoices.isEmpty()) {
                                Toast.makeText(context, "Žiadne doklady na exportovanie", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            isExportingPdf = true
                            scope.launch {
                                val res = PdfExporter.exportInvoicesToPdf(context, filteredInvoices, periodLabel)
                                isExportingPdf = false
                                when (res) {
                                    is PdfExportResult.Empty -> Toast.makeText(
                                        context,
                                        "Žiadne doklady na exportovanie",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    is PdfExportResult.Error -> Toast.makeText(
                                        context,
                                        "Chyba pri generovaní PDF: ${res.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    is PdfExportResult.Success -> Unit
                                }
                            }
                        },
                        modifier = Modifier.testTag("btn_export_pdf"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Exportovať do PDF",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isExportingPdf) "Generujem..." else "Exportovať PDF",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_invoices_input"),
                    placeholder = { Text("Vyhľadať dodávateľa, položku...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Hľadať",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Vymazať",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(slovakCategories) { category ->
                        FilterChipCustom(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = category
                        )
                    }
                }
            }

            if (filteredInvoices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = "Prázdny stav",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty() || selectedCategory != "Všetko" || selectedMonthIdx != null) {
                                    "Nenašli sa žiadne doklady"
                                } else {
                                    "Žiadne zaevidované doklady"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty() || selectedCategory != "Všetko" || selectedMonthIdx != null) {
                                    "Skúste upraviť vyhľadávací výraz, kategóriu alebo zrušiť mesačný filter."
                                } else {
                                    "Stlačte tlačidlo 'Bleskový Sken' a odfotografujte svoj prvý doklad."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            } else {
                items(filteredInvoices, key = { it.id }) { invoice ->
                    InvoiceItemRow(
                        invoice = invoice,
                        onClick = { selectedInvoiceForDetail = invoice },
                        onDelete = { viewModel.deleteInvoice(invoice.id) }
                    )
                }
            }
        }
    }

    selectedInvoiceForDetail?.let { invoice ->
        InvoiceDetailDialog(
            invoice = invoice,
            onDismiss = { if (!isSavingInvoiceDetail) selectedInvoiceForDetail = null },
            isSaving = isSavingInvoiceDetail,
            onSave = { updated ->
                if (!isSavingInvoiceDetail) {
                    isSavingInvoiceDetail = true
                    viewModel.saveInvoice(
                        updated,
                        onSaved = {
                            isSavingInvoiceDetail = false
                            selectedInvoiceForDetail = null
                        },
                        onError = { message ->
                            isSavingInvoiceDetail = false
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        )
    }
}
