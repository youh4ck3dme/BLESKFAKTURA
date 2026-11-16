# Aplikovanie Android patchu

Patch je **neaplikovaný**. Tento návod ho nespúšťa a nemení `applicationId`, podpisovanie ani Firebase projekt.

Súbor: `bleskova-efaktura-bridge/integration/android-app.patch`

Overenie, že sadne na čistý `grokbranch`:

```bash
git apply --check bleskova-efaktura-bridge/integration/android-app.patch
```

Aplikovanie, až keď ho chceš naozaj:

```bash
git apply bleskova-efaktura-bridge/integration/android-app.patch
```

## Čo patch robí

- `settings.gradle.kts` pridá `includeBuild("bleskova-efaktura-bridge/android/efaktura-client")`.
- JVM modul **nepreklápa** na `com.android.library`. Je to čistý JAR (`kotlin("jvm")`, toolchain JDK 17). Klient nepoužíva Android API. `include(":efaktura-client")` by narazilo na vlastný `settings.gradle.kts` modulu. Aplikácia je Java 11; či AGP 9.1.1 prehltne JAR s bytecode 17, **nie je overené** (chýba Android SDK). Ak `assembleDebug` hlási class file 61, treba v klientovi naraz znížiť Kotlin aj Java target na 11, nie len jeden z nich.
- `app/build.gradle.kts` závisí od `sk.bizagent.efaktura:efaktura-client:0.1.0`. Included build tú súradnicu nahradí lokálnym JAR.
- Funkcia je **predvolene vypnutá** (`EFAKTURA_BRIDGE_ENABLED=false`), aj v debug.
- Release polia sú natvrdo prázdne a `false`. `local.properties` sa do release neprenáša.
- HMAC, base URL a tenant sa čítajú len v debug z gitignorovaného `local.properties`. V zdrojákoch nie sú.

Voliteľné zapnutie len na lokálnom stroji, súbor `local.properties` (necommitovať):

```properties
EFAKTURA_BRIDGE_ENABLED=true
EFAKTURA_BASE_URL=http://10.0.2.2:8088
EFAKTURA_TENANT_ID=tenant-buyer
EFAKTURA_EMULATOR_SECRET=local-only-not-a-production-secret
```

`10.0.2.2` je len príklad emulátora na localhost hostiteľa. Secret je lokálny HMAC emulátora, nie Firebase.

## Čo používateľ uvidí

Keď je prepínač zapnutý a konfigurácia úplná, na dashboarde pribudne vstup eFaktúra.

- Trvalý text: „Lokálny testovací režim — nejde o produkčné doručenie.“
- `technicalPass=true` je text „Technická kontrola prešla … Nie je to legislatívne platná faktúra.“
- Slovenská vrstva je samostatne. Dnes je `UNSUPPORTED` (ruleset sa nespúšťa).
- Doručenie je samostatná karta. Sandbox ostáva simulovaný, `productionReceipt=false`.
- XML ide cez systémový picker (`OpenDocument`), nie cez vlastné úložisko.
- Zrušenie zastaví bežiacu operáciu. Zmena `uid` zruší prácu a zmaže stav.
- Vypnutý alebo neúplný debug ukáže, že funkcia je vypnutá. Release klient nevytvorí.

Room migrácia sa neaplikuje. Existujúca tabuľka `invoices` ostáva version 1.

## Čo nie je overené

V tomto prostredí nie je Android SDK. AGP 9.1.1 kompatibilita patchu **nie je** preložená. Neoznačuj ju za zelenú, kým lokálne neprejde `./gradlew :app:assembleDebug` s nainštalovaným SDK.
