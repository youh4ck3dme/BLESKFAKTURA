# Integrácia do Bleskovej Faktúry

Patch je **neaplikovaný**. Konkrétny diff je `integration/android-app.patch`. Návod na aplikovanie je `integration/APPLY.md`. Vetva `grokbranch` ním nemení hlavnú aplikáciu. CI ho aplikuje len vo svojom workeri.

Nemení sa:

- `applicationId` (`sk.bizagent.bleskovafaktura` v aktuálnom `app/build.gradle.kts`)
- podpis, `google-services`, Firebase projekt
- `AppDatabase` version 1, tabuľka `invoices`, existujúce UI a ViewModely (kým sa patch ručne neaplikuje)

Existujúci model `Invoice.totalAmount: Double` nie je doména eFaktúry.

## Prečo included build, nie Android knižnica

Klient je JVM modul s vlastným `settings.gradle.kts`. `include(":efaktura-client")` by ho nepripojil ako podprojekt. `com.android.library` by bol zbytočný: modul nemá Android API. Patch preto používa:

```kotlin
includeBuild("bleskova-efaktura-bridge/android/efaktura-client")
```

a závislosť `sk.bizagent.efaktura:efaktura-client:0.1.0`. Aplikácia kompiluje na Java 11, klient na toolchain 17. Či AGP 9.1.1 prehltne bytecode 17, overuje až Android CI, nie `git apply --check`.

## Čo patch zapína, keď ho aplikuješ

Predvolene je funkcia vypnutá, aj v debug. Release má `EFAKTURA_BRIDGE_ENABLED=false` a prázdne URL, tenant aj secret. HMAC a sandbox idú len z gitignorovaného `local.properties` v debug.

Cleartext HTTP je v release zakázaný. Debug pridá len `networkSecurityConfig` s `base-config cleartextTrafficPermitted=false` a výnimkou pre `127.0.0.1`, `localhost`, `10.0.2.2` a privátny host z debug URL. `usesCleartextTraffic` sa nenastavuje.

Obrazovka, keď je zapnutá, ukazuje:

„Lokálny testovací režim — nejde o produkčné doručenie.“

Technický text končí „Legislatívna platnosť nebola overená.“ Slovenská vrstva a doručenie sú oddelené. Zmena účtu zruší čítanie aj operáciu a vyčistí stav. XML ide cez systémový picker. Callback súbor nečíta. Čítanie je na `Dispatchers.IO`, limit 1 500 000 bajtov, zrušenie zatvorí stream a retry drží pôvodné bajty aj idempotentný kľúč.

UID v tokenoch je aktuálne UID používateľa, bez roly. Seed pozná `uid-buyer`, `uid-supplier` a `uid-reader`. Android UID sa zaradí len explicitne:

```bash
EFAKTURA_LOCAL_PROVISION_UID=<uid>
EFAKTURA_LOCAL_PROVISION_TENANT=tenant-buyer
EFAKTURA_LOCAL_PROVISION_ROLE=owner
```

To ide len s lokálnym seedom a len do syntetického tenanta. Produkcia tieto premenné odmietne. Cudzie UID ostane 403.

Room migrácia ostáva len plán v `examples/android-integration/RoomMigrationPlan.kt`. Neaplikovať.

## Neskorší produkčný klient

Toto nie je súčasť patchu a nie je to zelený test. Firebase Auth a App Check nie sú overené proti reálnemu projektu. Až bude server overovať Firebase, klient má brať token takto. HMAC emulátor do release nepatrí.

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
Každé volanie klienta posiela `X-Tenant-Id` aj cestu `/v1/tenants/{id}`. Ani jedno nie je členstvo. Server načíta členstvo uid vo firme. Klient nesmie posielať rolu ako dôkaz a token nesmie členstvo vytvoriť.
`sendDocument` vracia doklad. Stav je v `document.delivery`, nie ako top-level `DeliveryDto`. Samotný stav číta `getDeliveryState`. `ValidationReportDto` nesie `layers` a `issues`, slovenská vrstva je `SK`.
