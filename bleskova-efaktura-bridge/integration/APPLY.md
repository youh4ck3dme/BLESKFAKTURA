# Aplikovanie Android patchu

Patch je **neaplikovaný** na `grokbranch`. Tento návod ho nespúšťa a nemení `applicationId`, podpisovanie ani Firebase projekt.

Súbor: `bleskova-efaktura-bridge/integration/android-app.patch`

Overenie, že sadne na čistý `grokbranch`:

```bash
git apply --check bleskova-efaktura-bridge/integration/android-app.patch
```

Aplikovanie, až keď ho chceš naozaj:

```bash
git apply bleskova-efaktura-bridge/integration/android-app.patch
```

`git apply --check` nie je zostavenie. Android zostavenie patchu ide v izolovanom worktree alebo v CI jobe `.github/workflows/efaktura-android.yml`, ktorý patch aplikuje len vo svojom checkoute a nepushuje ho späť.

## Čo patch robí

- `settings.gradle.kts` pridá `includeBuild("bleskova-efaktura-bridge/android/efaktura-client")`.
- JVM modul **nepreklápa** na `com.android.library`. Je to čistý JAR (`kotlin("jvm")`, toolchain JDK 17). Klient nepoužíva Android API.
- `app/build.gradle.kts` závisí od `sk.bizagent.efaktura:efaktura-client:0.1.0`. Included build tú súradnicu nahradí lokálnym JAR.
- Funkcia je **predvolene vypnutá** (`EFAKTURA_BRIDGE_ENABLED=false`), aj v debug.
- Release polia sú natvrdo prázdne a `false`. `local.properties` sa do release neprenáša.
- HMAC, base URL a tenant sa čítajú len v debug z gitignorovaného `local.properties`. V zdrojákoch nie sú.
- Debug manifest len v `app/src/debug` nastaví `android:networkSecurityConfig`. Release source set túto výnimku nemá. `usesCleartextTraffic` sa nenastavuje ani v main, ani v debug.
- Debug Gradle úloha vygeneruje `network_security_config.xml` s `base-config cleartextTrafficPermitted=false`. Cleartext je povolený len pre `127.0.0.1`, `localhost`, `10.0.2.2` a host z `EFAKTURA_BASE_URL`, ak je to loopback, `10.0.2.2` alebo RFC1918 (`10/8`, `172.16/12`, `192.168/16`). Verejný host sa nepridá.

Voliteľné zapnutie len na lokálnom stroji, súbor `local.properties` (necommitovať):

```properties
EFAKTURA_BRIDGE_ENABLED=true
EFAKTURA_BASE_URL=http://10.0.2.2:8088
EFAKTURA_TENANT_ID=tenant-buyer
EFAKTURA_EMULATOR_SECRET=local-only-not-a-production-secret
```

`10.0.2.2` je emulátor na localhost hostiteľa. Secret je lokálny HMAC emulátora, nie Firebase. HTTP na verejnú adresu debug konfigurácia nepustí.

Na serveri, len pri `EFAKTURA_ENV=local` a zapnutom lokálnom seede, sa aktuálne UID zaradí explicitne. Token ho nezaradí. Produkcia premenné odmietne:

```bash
EFAKTURA_LOCAL_PROVISION_UID=<uid z aplikácie>
EFAKTURA_LOCAL_PROVISION_TENANT=tenant-buyer
EFAKTURA_LOCAL_PROVISION_ROLE=owner
```

Import potrebuje rolu `owner`. `reader` smie len čítať. Iné tenanty ako `tenant-buyer` a `tenant-supplier` provisioning odmietne.

## Čo používateľ uvidí

Keď je prepínač zapnutý a konfigurácia úplná, na dashboarde pribudne vstup eFaktúra.

- Trvalý text: „Lokálny testovací režim — nejde o produkčné doručenie.“
- Technický text končí „Legislatívna platnosť nebola overená.“ Nehovorí, že faktúra je právne neplatná.
- Obrazovka ukáže aktuálne UID a že server ho nezaradí, kým nie sú nastavené `EFAKTURA_LOCAL_PROVISION_*`.
- Slovenská vrstva je samostatne. Dnes je `UNSUPPORTED` (ruleset sa nespúšťa).
- Doručenie je samostatná karta. Sandbox ostáva simulovaný, `productionReceipt=false`.
- XML ide cez systémový picker. Callback nenesie bajty. ViewModel otvorí stream a číta ho cez `Dispatchers.IO` s limitom 1 500 000 bajtov. Zrušenie zatvorí stream. Chyba limitu sa zobrazí. Retry pošle pôvodné bajty a pôvodný idempotentný kľúč.
- Zmena `uid` zruší čítanie aj bežiacu operáciu a zmaže stav.
- Vypnutý alebo neúplný debug ukáže, že funkcia je vypnutá. Release klient nevytvorí. Token nesie uid a žiadnu rolu.

Room migrácia sa neaplikuje. Existujúca tabuľka `invoices` ostáva version 1.

## Zostavenie

V čistom worktree, nie na vetve, ktorú pushuješ:

```bash
git worktree add --detach /tmp/efaktura-android HEAD
cd /tmp/efaktura-android
git apply bleskova-efaktura-bridge/integration/android-app.patch
cp .env.example .env
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest \
  --no-daemon --no-configuration-cache \
  --tests com.example.ExampleUnitTest \
  --tests com.example.ExampleRobolectricTest \
  --tests com.example.InvoiceAiValidationTest
```

Potrebné je Gradle 9.3.1, JDK 17, SDK platform `android-36` aj `android-36.1`, Build Tools 36.0.0. `applicationId` ostáva `sk.bizagent.bleskovafaktura`. Podpis debug ide existujúcou `debugConfig` (alias `androiddebugkey`). Upload keystore sa nemení.
