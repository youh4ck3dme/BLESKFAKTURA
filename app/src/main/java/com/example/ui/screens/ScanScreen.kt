package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.data.ExtractedInvoiceJson
import com.example.data.Invoice
import com.example.data.ReceiptImageProcessor
import com.example.data.SlovakFinanceUtils
import com.example.ui.InvoiceViewModel
import com.example.ui.ScanUiState
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: InvoiceViewModel,
    onNavigateBack: () -> Unit
) {
    BackHandler {
        viewModel.startNewScan()
        onNavigateBack()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanState by viewModel.scanState.collectAsState()

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var stagedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCaptureUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var stagedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var stagedFileName by rememberSaveable { mutableStateOf("") }
    var stagedFileSize by rememberSaveable { mutableStateOf("") }
    var isDecodingImage by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }

    // Clear staged file when ScanState resets to Idle without pending URI
    LaunchedEffect(scanState) {
        if (scanState is ScanUiState.Success || scanState is ScanUiState.Error) {
            // Keep preview reference until user cancels/saves
        }
    }

    // Re-decode saved URI after configuration change (rotation) if needed
    LaunchedEffect(stagedUriString) {
        val uriStr = stagedUriString
        if (!uriStr.isNullOrBlank() && stagedBitmap == null) {
            isDecodingImage = true
            runCatching {
                ReceiptImageProcessor.decodeBoundedBitmapFromUri(context, Uri.parse(uriStr), stagedFileName.ifBlank { "doklad.jpg" })
            }.onSuccess { decoded ->
                stagedBitmap = decoded.bitmap
                if (stagedFileName.isBlank()) stagedFileName = decoded.displayName
                if (stagedFileSize.isBlank()) stagedFileSize = decoded.formattedSize
            }.onFailure {
                stagedUriString = null
            }
            isDecodingImage = false
        }
    }

    fun loadImageFromUri(uri: Uri, fallbackName: String) {
        scope.launch {
            isDecodingImage = true
            runCatching {
                ReceiptImageProcessor.decodeBoundedBitmapFromUri(context, uri, fallbackName)
            }.onSuccess { decoded ->
                stagedUriString = uri.toString()
                stagedBitmap = decoded.bitmap
                stagedFileName = decoded.displayName
                stagedFileSize = decoded.formattedSize
                cameraPermissionDenied = false
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    err.localizedMessage ?: "Obrázok sa nepodarilo načítať.",
                    Toast.LENGTH_LONG
                ).show()
            }
            isDecodingImage = false
        }
    }

    // Zero-permission Photo Picker (BF-14)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { loadImageFromUri(it, "doklad_galeria.jpg") }
    }

    // Full-resolution TakePicture launcher via FileProvider (BF-32)
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val captureUri = pendingCaptureUriString?.let { Uri.parse(it) }
        if (success && captureUri != null) {
            loadImageFromUri(captureUri, "fotoaparat_doklad.jpg")
        }
        pendingCaptureUriString = null
    }

    fun launchFullResCamera() {
        runCatching {
            val (uri, _) = ReceiptImageProcessor.createCameraCaptureUri(context)
            pendingCaptureUriString = uri.toString()
            takePictureLauncher.launch(uri)
        }.onFailure { err ->
            Toast.makeText(
                context,
                "Fotoaparát sa nepodarilo spustiť: ${err.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Runtime CAMERA permission request (BF-05)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            cameraPermissionDenied = false
            launchFullResCamera()
        } else {
            cameraPermissionDenied = true
        }
    }

    fun requestCameraAndCapture() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraPermissionDenied = false
            launchFullResCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.shouldAutoLaunchCamera) {
            viewModel.shouldAutoLaunchCamera = false
            requestCameraAndCapture()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("scan_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Bleskový AI skener", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.startNewScan()
                            onNavigateBack()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Späť",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            if (scanState is ScanUiState.Idle && stagedBitmap == null && !isDecodingImage) {
                ExtendedFloatingActionButton(
                    onClick = { requestCameraAndCapture() },
                    modifier = Modifier
                        .padding(8.dp)
                        .testTag("fab_instant_camera_capture"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Odfotiť nový doklad"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Odfotiť nový doklad",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = scanState) {
                is ScanUiState.Idle -> {
                    if (isDecodingImage) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Optimalizujem rozlíšenie snímky...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else if (stagedBitmap != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Spacer(modifier = Modifier.height(6.dp))

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                ),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Doklad bol pripravený v plnom rozlíšení. Spustite AI analýzu nižšie.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Image(
                                        bitmap = stagedBitmap!!.asImageBitmap(),
                                        contentDescription = "Náhľad vybraného dokladu",
                                        modifier = Modifier
                                            .size(160.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .border(
                                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                                RoundedCornerShape(16.dp)
                                            ),
                                        contentScale = ContentScale.Crop
                                    )

                                    Spacer(modifier = Modifier.height(24.dp))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = "Súbor",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stagedFileName,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (stagedFileSize.isNotEmpty()) {
                                                    Text(
                                                        text = stagedFileSize,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                stagedUriString = null
                                                stagedBitmap = null
                                                stagedFileName = ""
                                                stagedFileSize = ""
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("clear_staged_file_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Odstrániť súbor",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val bmp = stagedBitmap ?: return@Button
                                        selectedBitmap = bmp
                                        viewModel.scanInvoiceImage(bmp)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("analyze_with_ai_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "AI analýza",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Extrahovať dáta pomocou AI",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        galleryLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("change_staged_file_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.Collections, contentDescription = "Galéria")
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Zvoliť iný súbor z galérie", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (cameraPermissionDenied) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("camera_permission_rationale")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = "Prístup k fotoaparátu bol zamietnutý. Udeľte povolenie v nastaveniach zariadenia alebo vyberte fotku z galérie.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "AI",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Umelá inteligencia Blesk",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Odfoťte pokladničný blok alebo faktúru. AI prečíta text, sumu, DPH, IČO aj kategóriu.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(
                                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                                        shape = RoundedCornerShape(24.dp)
                                    )
                                    .clickable {
                                        galleryLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FilterCenterFocus,
                                        contentDescription = "Zameranie",
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(72.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Kliknite sem a vyberte fotku dokladu z galérie",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Uistite sa, že písmo je dobre čitateľné a svetlo je dostatočné.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { requestCameraAndCapture() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("scan_camera_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = "Fotoaparát")
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Odfotiť nový doklad", fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        galleryLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("scan_gallery_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.Collections, contentDescription = "Galéria")
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Vybrať fotku z galérie", fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (BuildConfig.DEBUG) {
                                    Button(
                                        onClick = {
                                            val bitmap = generateSampleReceiptBitmap()
                                            stagedUriString = null
                                            stagedBitmap = bitmap
                                            stagedFileName = "vzorovy_tesco_doklad.png"
                                            stagedFileSize = "23.4 KB"
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .testTag("scan_mock_sample_button"),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary,
                                            contentColor = MaterialTheme.colorScheme.onSecondary
                                        ),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(imageVector = Icons.Default.Receipt, contentDescription = "Vzor")
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text("Simulovať vzorový doklad (Tesco)", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                is ScanUiState.Scanning -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        selectedBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Nahrávaný doklad",
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                        }

                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(54.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "BLESKOVÁ ANALÝZA DOKLADU",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.progressMessage,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                is ScanUiState.Success -> {
                    var isSaving by remember { mutableStateOf(false) }
                    ReviewScannedInvoiceForm(
                        extracted = state.extractedData,
                        bitmap = state.bitmap,
                        isSaving = isSaving,
                        onCancel = {
                            stagedUriString = null
                            stagedBitmap = null
                            viewModel.startNewScan()
                        },
                        onConfirm = { invoice ->
                            if (isSaving) return@ReviewScannedInvoiceForm
                            isSaving = true
                            viewModel.saveScannedInvoice(
                                invoice = invoice,
                                bitmap = state.bitmap,
                                onError = { message ->
                                    isSaving = false
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                },
                                onSaved = {
                                    isSaving = false
                                    stagedUriString = null
                                    stagedBitmap = null
                                    onNavigateBack()
                                }
                            )
                        }
                    )
                }

                is ScanUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Chyba",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Vyskytla sa chyba",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { viewModel.startNewScan() },
                            modifier = Modifier
                                .width(200.dp)
                                .height(48.dp)
                                .testTag("retry_scan_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Skúsiť znova", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewScannedInvoiceForm(
    extracted: ExtractedInvoiceJson,
    bitmap: Bitmap,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onConfirm: (Invoice) -> Unit
) {
    // A retry after a partial cloud failure must update the same local document.
    val invoiceId = rememberSaveable(extracted) { UUID.randomUUID().toString() }
    val initialTotalCents = remember(extracted) {
        SlovakFinanceUtils.doubleToCents(extracted.totalAmount)
    }
    val initialVatRate = remember(extracted) {
        extracted.vatRate ?: 20.0
    }
    val initialVatCents = remember(extracted) {
        extracted.vatAmount?.let { SlovakFinanceUtils.doubleToCents(it) }
            ?: SlovakFinanceUtils.calculateVatCentsFromGross(initialTotalCents, BigDecimal.valueOf(initialVatRate))
    }

    var supplierName by rememberSaveable { mutableStateOf(extracted.supplierName) }
    var ico by rememberSaveable { mutableStateOf(extracted.ico ?: "") }
    var dic by rememberSaveable { mutableStateOf(extracted.dic ?: "") }
    var totalAmountStr by rememberSaveable { mutableStateOf(SlovakFinanceUtils.formatCentsForInput(initialTotalCents)) }
    var vatRateStr by rememberSaveable { mutableStateOf(initialVatRate.toString().replace('.', ',')) }
    var vatAmountStr by rememberSaveable { mutableStateOf(SlovakFinanceUtils.formatCentsForInput(initialVatCents)) }
    var issueDate by rememberSaveable { mutableStateOf(extracted.issueDate) }
    var dueDate by rememberSaveable { mutableStateOf("") }
    var isPaid by rememberSaveable { mutableStateOf(true) }
    var category by rememberSaveable { mutableStateOf(extracted.category) }
    var description by rememberSaveable { mutableStateOf(extracted.description ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    var isMenuExpanded by remember { mutableStateOf(false) }
    val categories = listOf("Kancelária", "Cestovné", "Služby", "Technika", "Strava", "Iné")

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag("review_scanned_form"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Naskenovaný doklad",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "AI extrakcia úspešná",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Skontrolujte extrahovanú sumu, DPH a splatnosť pred uložením.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        validationError?.let { err ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scan_validation_error")
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = supplierName,
                onValueChange = { supplierName = it; validationError = null },
                label = { Text("Názov dodávateľa") },
                modifier = Modifier.fillMaxWidth().testTag("scanned_supplier_input"),
                singleLine = true,
                colors = fieldColors
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ico,
                    onValueChange = { ico = it },
                    label = { Text("IČO") },
                    modifier = Modifier.weight(1f).testTag("scanned_ico_input"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = dic,
                    onValueChange = { dic = it },
                    label = { Text("DIČ / IČ DPH") },
                    modifier = Modifier.weight(1f).testTag("scanned_dic_input"),
                    singleLine = true,
                    colors = fieldColors
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = totalAmountStr,
                    onValueChange = { totalAmountStr = it; validationError = null },
                    label = { Text("Suma s DPH (€)") },
                    modifier = Modifier.weight(1.1f).testTag("scanned_amount_input"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = vatRateStr,
                    onValueChange = { vatRateStr = it; validationError = null },
                    label = { Text("Sadzba (%)") },
                    modifier = Modifier.weight(0.75f).testTag("scanned_vat_rate_input"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColors
                )
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = vatAmountStr,
                    onValueChange = { vatAmountStr = it; validationError = null },
                    label = { Text("Suma DPH (€)") },
                    modifier = Modifier.weight(1f).testTag("scanned_vat_amount_input"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColors
                )
                TextButton(
                    onClick = {
                        val totalDec = SlovakFinanceUtils.parseSlovakDecimal(totalAmountStr)
                        val rateDec = SlovakFinanceUtils.parseSlovakDecimal(vatRateStr)
                        if (totalDec != null && totalDec > BigDecimal.ZERO && rateDec != null && rateDec >= BigDecimal.ZERO) {
                            val totalCents = SlovakFinanceUtils.bigDecimalToCents(totalDec)
                            val calcVat = SlovakFinanceUtils.calculateVatCentsFromGross(totalCents, rateDec)
                            vatAmountStr = SlovakFinanceUtils.formatCentsForInput(calcVat)
                            validationError = null
                        } else {
                            validationError = "Pre výpočet DPH zadajte platnú celkovú sumu a sadzbu."
                        }
                    },
                    modifier = Modifier.testTag("recalculate_scan_vat_button")
                ) {
                    Text("Prepočítať", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = issueDate,
                    onValueChange = { issueDate = it; validationError = null },
                    label = { Text("Dátum vystavenia") },
                    modifier = Modifier.weight(1f).testTag("scanned_date_input"),
                    singleLine = true,
                    colors = fieldColors
                )

                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it; validationError = null },
                    label = { Text("Splatnosť (volit.)") },
                    placeholder = { Text("YYYY-MM-DD") },
                    modifier = Modifier.weight(1f).testTag("scanned_due_date_input"),
                    singleLine = true,
                    colors = fieldColors
                )
            }
        }

        item {
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
                                    contentDescription = "Vybrať kategóriu"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isMenuExpanded = !isMenuExpanded }
                            .testTag("scanned_category_selector"),
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(
                        text = "Uhradené",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = isPaid,
                        onCheckedChange = { isPaid = it },
                        modifier = Modifier.testTag("scanned_paid_switch")
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Popis výdavku") },
                modifier = Modifier.fillMaxWidth().testTag("scanned_description_input"),
                colors = fieldColors
            )
        }

        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f).height(52.dp).testTag("cancel_scanned_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Vymazať")
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
                                validationError = "Suma DPH nemôže byť vyššia než celková suma s DPH."
                                return@Button
                            }
                            vc
                        }

                        val normalizedIssue = SlovakFinanceUtils.normalizeIsoDate(issueDate)
                        if (normalizedIssue == null) {
                            validationError = "Neplatný dátum vystavenia (použite RRRR-MM-DD alebo DD.MM.RRRR)."
                            return@Button
                        }

                        val normalizedDue = if (dueDate.isBlank()) {
                            null
                        } else {
                            SlovakFinanceUtils.normalizeIsoDate(dueDate) ?: run {
                                validationError = "Neplatný dátum splatnosti (použite RRRR-MM-DD alebo DD.MM.RRRR)."
                                return@Button
                            }
                        }

                        onConfirm(
                            Invoice(
                                id = invoiceId,
                                supplierName = trimmedSupplier,
                                ico = ico.trim().ifBlank { null },
                                dic = dic.trim().ifBlank { null },
                                totalAmount = SlovakFinanceUtils.centsToDouble(totalCents),
                                totalAmountCents = totalCents,
                                vatRate = parsedRate.toDouble(),
                                vatAmount = vatCents?.let { SlovakFinanceUtils.centsToDouble(it) },
                                vatAmountCents = vatCents,
                                issueDate = normalizedIssue,
                                dueDate = normalizedDue,
                                isPaid = isPaid,
                                currency = "EUR",
                                category = category,
                                description = description.trim().ifBlank { null }
                            )
                        )
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1.3f).height(52.dp).testTag("confirm_scanned_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Potvrdiť a uložiť", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

fun generateSampleReceiptBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.parseColor("#FAF9F6"))

    val paint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY
        textSize = 14f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
    }

    val titlePaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.BLACK
        textSize = 22f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    val subtitlePaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.GRAY
        textSize = 11f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    var y = 40f
    canvas.drawText("TESCO STORES SR, a.s.", 200f, y, titlePaint)
    y += 24f
    canvas.drawText("Prevádzka: Kamenné Námestie, Bratislava", 200f, y, subtitlePaint)
    y += 18f
    canvas.drawText("IČO: 31321828  IČ DPH: SK2020300125", 200f, y, subtitlePaint)
    y += 25f

    paint.strokeWidth = 2f
    canvas.drawLine(30f, y, 370f, y, paint)
    y += 25f

    paint.textSize = 13f
    canvas.drawText("Kancelársky papier A4 (Reflex)  1 ks", 30f, y, paint)
    canvas.drawText("5.90 A", 300f, y, paint)
    y += 22f
    canvas.drawText("Blok poznámkový linajkový       5 ks", 30f, y, paint)
    canvas.drawText("12.50 A", 300f, y, paint)
    y += 22f
    canvas.drawText("USB Kľúč Kingston 64GB          1 ks", 30f, y, paint)
    canvas.drawText("18.00 A", 300f, y, paint)
    y += 22f
    canvas.drawText("Káva mletá Jacobs Krönung      1 ks", 30f, y, paint)
    canvas.drawText("9.50 B", 300f, y, paint)
    y += 30f

    canvas.drawLine(30f, y, 370f, y, paint)
    y += 30f

    val boldTotalPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.BLACK
        textSize = 18f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }

    canvas.drawText("CELKOM K ÚHRADE:", 30f, y, boldTotalPaint)
    canvas.drawText("45.90 EUR", 270f, y, boldTotalPaint)
    y += 26f

    paint.textSize = 12f
    canvas.drawText("Sadzba DPH       Bez DPH      DPH      S DPH", 30f, y, paint)
    y += 18f
    canvas.drawText("A (20.0%)        30.33     6.07     36.40", 30f, y, paint)
    y += 18f
    canvas.drawText("B (10.0%)         8.64     0.86      9.50", 30f, y, paint)
    y += 30f

    canvas.drawLine(30f, y, 370f, y, paint)
    y += 25f

    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    canvas.drawText("Dátum vystavenia: $date", 30f, y, paint)
    y += 18f
    canvas.drawText("DKP: 6012020300125010   Číslo dokladu: 4529", 30f, y, paint)
    y += 32f

    canvas.drawText("ĎAKUJEME ZA NÁKUP!", 200f, y, subtitlePaint)
    return bitmap
}
