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

Klient sa skladá s base URL bridge API, nie s Firebase project ID v knižnici:

```kotlin
val client = HttpEfakturaClient(
    baseUrl = BuildConfig.EFAKTURA_BASE_URL,
    tenantId = activeTenantId,
    authTokens = AuthTokenProvider { firebaseUser.getIdToken(false).await() },
    appCheckTokens = AppCheckTokenProvider { FirebaseAppCheck.getInstance().getToken(false).await().token }
)
```

Volanie z obrazovky ide cez `examples/android-integration/EfakturaImportViewModel.kt`. Pri zmene účtu sa job zruší. Release nesmie mať demo login.

Room: `examples/android-integration/RoomMigrationPlan.kt`. Migrácia 1 → 2 pridá `efaktura_documents` a `efaktura_notes`. XML ostáva celé podľa sha256. Poznámka nie je súčasť originálu. Rollback neresetuje `invoices`.

Server musí načítať členstvo uid vo firme. Samotný Firebase token nestačí a klient nesmie posielať rolu ako dôkaz.
