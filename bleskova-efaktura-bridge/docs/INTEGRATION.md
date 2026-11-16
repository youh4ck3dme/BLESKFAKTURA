# Integrácia do Bleskovej Faktúry

Patch je **neaplikovaný**. Vetva `grokbranch` pridáva len adresár `bleskova-efaktura-bridge/`.

Nemení sa:

- `applicationId` (`sk.bizagent.bleskovafaktura` v aktuálnom `app/build.gradle.kts`)
- podpis, `google-services`, Firebase projekt
- `AppDatabase` version 1, tabuľka `invoices`, UI, ViewModely

Existujúci model `Invoice.totalAmount: Double` nie je doména eFaktúry.

## Minimálny patch, ktorý treba spraviť pri release

`settings.gradle.kts` (neaplikované):

```kotlin
include(":app")
include(":efaktura-client")
project(":efaktura-client").projectDir = file("bleskova-efaktura-bridge/android/efaktura-client")
```

`app/build.gradle.kts` dependencies (neaplikované), až keď bude modul Android-kompatibilný alebo cez included build:

```kotlin
implementation(project(":efaktura-client"))
```

Klient sa skladá s base URL bridge API, nie s Firebase project ID v knižnici.
Firebase Auth a App Check v tomto module **nie sú otestované** proti reálnemu projektu. Príklad nižšie je len kompilovateľný náčrt, nie zelený test.

```kotlin
val expectedUid = firebaseUser.uid
val client = HttpEfakturaClient(
    baseUrl = BuildConfig.EFAKTURA_BASE_URL,
    tenantId = activeTenantId,
    authTokens = AuthTokenProvider {
        val current = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Žiadny prihlásený používateľ")
        if (current.uid != expectedUid) throw IllegalStateException("Účet sa počas operácie zmenil")
        val token = current.getIdToken(false).await().token
        token ?: throw IllegalStateException("Firebase vrátil prázdny token")
    },
    appCheckTokens = AppCheckTokenProvider {
        val result = FirebaseAppCheck.getInstance().getToken(false).await()
        result.token ?: throw IllegalStateException("App Check vrátil prázdny token")
    }
)
```

`getIdToken(false).await()` vracia `GetTokenResult`, nie `String`. Do klienta ide až `.token`.
Každé volanie klienta posiela `X-Tenant-Id` aj cestu `/v1/tenants/{id}`. Ani jedno nie je dôkaz členstva. Server načíta členstvo uid vo firme. Klient nesmie posielať rolu ako dôkaz.

`sendDocument` vracia doklad. Stav je v `document.delivery`, nie ako top-level `DeliveryDto`. Samotný stav číta `getDeliveryState`.

Volanie z obrazovky ide cez `examples/android-integration/EfakturaImportViewModel.kt`. Nový súbor dostane nový `Idempotency-Key`. Retry tej istej operácie použije pôvodný kľúč. Pri zmene účtu sa job zruší. Release nesmie mať demo login. ViewModel nie je zapojený do aplikácie.

Room: `examples/android-integration/RoomMigrationPlan.kt`. Migrácia 1 → 2 pridá `efaktura_documents` a `efaktura_notes`. XML ostáva celé podľa sha256. Poznámka nie je súčasť originálu. Rollback neresetuje `invoices`.

Server musí načítať členstvo uid vo firme. Samotný Firebase token nestačí a klient nesmie posielať rolu ako dôkaz.
