package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.InvoiceViewModel
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: InvoiceViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPricing: () -> Unit
) {
    val context = LocalContext.current
    val supportEmail = stringResource(R.string.support_email)
    val privacyPolicyUrl = stringResource(R.string.privacy_policy_url).trim()
    val hasLivePrivacyUrl = privacyPolicyUrl.startsWith("https://")

    val currentUser by viewModel.currentUser.collectAsState()
    val userStats by viewModel.userStats.collectAsState()

    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isLocalProfile = currentUser?.uid?.startsWith("demo_") == true
    BackHandler { if (!isDeleting) onNavigateBack() }

    val openSupportEmail = {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$supportEmail")
            putExtra(Intent.EXTRA_SUBJECT, "Blesková Faktúra — GDPR / Podpora")
        }
        runCatching {
            context.startActivity(emailIntent)
        }.onFailure {
            Toast.makeText(
                context,
                "Kontaktujte podporu na adrese $supportEmail",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nastavenia účtu", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("settings_back_button")
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
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings_list")
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profil",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = currentUser?.displayName ?: "Používateľ",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = currentUser?.email ?: "Lokálny profil",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (viewModel.isInternalTesting) {
                                "Interné testovanie bez nákupov • Dokladov: ${userStats.invoiceCount}"
                            } else if (userStats.isSubscribed) {
                                "Tarifa: Blesk Premium (aktívne)"
                            } else {
                                "Zostatok kreditov: ${userStats.credits} skenov • Dokladov: ${userStats.invoiceCount}"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedButton(
                            onClick = onNavigateToPricing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_pricing_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Text(if (viewModel.isInternalTesting) "O internej verzii" else "Spravovať predplatné a kredity", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PrivacyTip,
                                contentDescription = "Ochrana súkromia",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Ochrana súkromia a údaje",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = if (viewModel.isInternalTesting) "Snímky sa čítajú priamo v zariadení. Lokálny profil ukladá údaje iba do zariadenia a zachová ich aj po odhlásení až do vymazania profilu. Pri prihlásení cez Google sa údaje dokladov synchronizujú do Firestore; snímky sa do cloudovej AI neposielajú." else "Doklady sa ukladajú do súkromného úložiska zariadenia a pri Google účte aj do Firestore. Snímky sa pri cloudovej AI extrakcii odošlú na spracovanie cez Firebase AI Logic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { showPrivacyDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("privacy_policy_button")
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Zobraziť Zásady ochrany osobných údajov", fontWeight = FontWeight.Bold)
                        }
                        if (hasLivePrivacyUrl) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyUrl))
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("privacy_policy_web_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Otvoriť online verziu (HTTPS)", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        TextButton(
                            onClick = { openSupportEmail() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("support_email_button")
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Kontaktovať podporu / GDPR ($supportEmail)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { viewModel.signOut() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("settings_sign_out_button"),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Odhlásiť sa z účtu", fontWeight = FontWeight.Bold)
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Nebezpečná zóna",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (isLocalProfile) "Odstráni tento lokálny profil, uložené doklady a snímky zo zariadenia. Exporty uložené mimo aplikácie zostanú u ich príjemcov." else "Odstráni profil a doklady v cloude aj na zariadení, lokálne snímky a účet Firebase Auth. Vyžaduje sieť a nové overenie toho istého Google účtu. Exporty uložené mimo aplikácie zostanú u ich príjemcov.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = { showDeleteConfirm = true },
                            enabled = !isDeleting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("settings_delete_account_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onError,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.DeleteForever, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Vymazať účet a všetky dáta", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Zásady ochrany osobných údajov", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Blesková Faktúra (sk.bizagent.bleskovafaktura)\nKontakt: $supportEmail\n\n" +
                            "1. Spracúvané údaje: Google identifikátor účtu (UID, e-mail, meno), účtovné doklady (dodávateľ, IČO/DIČ, dátumy, sumy, DPH) a snímky dokladov.\n\n" +
                            (if (viewModel.isInternalTesting) {
                                "2. Spracovanie: Lokálne OCR pomocou pribaleného modelu ML Kit číta snímky v zariadení. Pri voliteľnom Google prihlásení slúži Firebase Auth na účet a Firestore na synchronizáciu údajov dokladov. Google SDK môžu spracúvať technické údaje podľa svojich zásad. Snímky sa neposielajú do cloudovej AI.\n\n"
                            } else {
                                "2. Spracovanie: Firebase Auth poskytuje Google prihlásenie, Firestore cloudovú synchronizáciu a Firebase AI Logic vyťaženie údajov zo snímok. Pri nákupoch sa používa Google Play Billing a serverové overenie.\n\n"
                            }) +
                            "3. Uchovávanie: Účet a doklady uchovávame počas používania. Dočasné PDF súbory sa čistia pri ďalšom exporte, odhlásení alebo výmaze.\n\n" +
                            "4. Výmaz: Zmazanie môžete spustiť v Nastaveniach alebo požiadať na $supportEmail. Cloudový výmaz vyžaduje sieť a nové overenie účtu Google. Po chybe môžete operáciu zopakovať; aplikácia oznámi výsledok. Kópie PDF uložené alebo zdieľané mimo aplikácie musíte odstrániť samostatne.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("Zavrieť", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            title = { Text("Natrvalo vymazať účet?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Táto akcia je nevratná. Odstráni údaje tohto profilu. " + if (isLocalProfile) "Lokálny profil nie je účet Google." else "Na ďalšej obrazovke potvrďte ten istý účet Google: ${currentUser?.email.orEmpty()}. Ak máte predplatné z inej verzie, spravujte jeho zrušenie osobitne v Google Play.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        isDeleting = true
                        scope.launch {
                            try {
                                val idToken = if (isLocalProfile) null else {
                                    val activity = context as? Activity
                                        ?: throw IllegalStateException("Overenie účtu vyžaduje aktívne okno aplikácie.")
                                    val clientId = context.getString(R.string.default_web_client_id)
                                    val option = GetSignInWithGoogleOption.Builder(clientId).build()
                                    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                                    val credential = CredentialManager.create(context).getCredential(activity, request).credential
                                    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                        throw IllegalStateException("Google nevrátil platné overenie účtu.")
                                    }
                                    GoogleIdTokenCredential.createFrom(credential.data).idToken
                                }
                                viewModel.deleteAccount(
                                    onSuccess = { isDeleting = false },
                                    onError = { msg ->
                                        isDeleting = false
                                        deleteErrorMessage = msg
                                    },
                                    reauthenticationIdToken = idToken
                                )
                            } catch (_: GetCredentialCancellationException) {
                                isDeleting = false
                            } catch (e: CancellationException) {
                                isDeleting = false
                                throw e
                            } catch (e: Exception) {
                                isDeleting = false
                                deleteErrorMessage = e.localizedMessage ?: "Overenie účtu sa nepodarilo. Skúste to znova."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text("Áno, vymazať všetko", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Zrušiť")
                }
            }
        )
    }

    deleteErrorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { deleteErrorMessage = null },
            title = { Text("Výmaz účtu vyžaduje overenie", fontWeight = FontWeight.Bold) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { deleteErrorMessage = null }) {
                    Text("Rozumiem", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
