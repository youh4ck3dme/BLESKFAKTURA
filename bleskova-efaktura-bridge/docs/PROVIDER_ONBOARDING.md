# Onboarding reálneho poštára

Provider zatiaľ nie je vybraný. `LocalSandboxProvider` je simulácia. `UnconfiguredProvider` blokuje odoslanie kódom `PROVIDER_NOT_CONFIGURED`. Sandbox sa v produkcii nespustí a nikdy nenastaví `productionReceipt=true`.

Budúci adapter implementuje `DeliveryProvider` a musí prejsť rovnaké očakávania ako testy webhooku, timeoutu a tenant izolácie:

1. Oficiálna API dokumentácia zvoleného certifikovaného poštára a presná verzia, ktorú budeme pinovať.
2. Sandbox credentials v Secret Manageri, nie v repe ani v URL. Iný secret pre webhook signing.
3. Podpis webhooku overený nad raw body, okno času, ochrana pred replay. Duplicitné a out-of-order udalosti nesmú vrátiť stav dozadu.
4. Mandát firmy: registrácia účastníka u poštára je samostatný právny krok, modul ju nesimuluje ako hotovú.
5. Idempotencia submitu. Ak ju poštár nepotvrdzuje, timeout po submit je `UNKNOWN` a ďalší krok je lookup, nie nová faktúra.
6. Oddeliť `PROVIDER_ACCEPTED`, transport `DELIVERED`, biznis odpoveď a reporting finančnej správe. Bez dokladu z konektora sa reporting nehlási.
7. Mapovanie slovenských pravidiel: dnes je FS XLSX v1.13 (2026-10-29) len uložený. Kým z neho nie je spúšťaný ruleset, produkčné send zostáva zatvorené aj pri zelenej EN16931/Peppol kontrole.
8. Zmluvný test: jeden pozitívny a jeden negatívny doklad v sandboxe poštára, plus zákaz produkčného endpointu v lokálnom compose.
