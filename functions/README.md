# Backend overovania Google Play nákupov

Tento priečinok obsahuje TypeScript implementáciu Firebase Cloud Functions v2
pre overovanie nákupov a predplatného. Prítomnosť kódu ani úspech jednotkových
testov nepotvrdzujú, že sú funkcie nasadené alebo že prešiel skutočný Play nákup.
Aktuálny výsledok a blokery sú v [stave projektu](../docs/project-status.md).

## Aktuálny rozsah: lokálne testy, billing 0 €

**Neaktivovať platený billing a nenasadzovať cloudové funkcie ani pravidlá.**
Deployment Cloud Functions vyžaduje Blaze podľa
[oficiálneho návodu Firebase](https://firebase.google.com/docs/functions/get-started).
Nasadenie preto nie je súčasťou tohto lokálneho postupu. Skript `deploy` v
`package.json` za aktuálneho rozhodnutia nespúšťaj.

Lokálne testy verifikátora používajú náhrady Play klienta a úložiska. Nevyžadujú
Play poverenia, platený projekt ani Firebase emulátor.

## Zostavenie a testovanie

Z koreňa repozitára, s Node.js 22 a pnpm 12.15.0:

```sh
pnpm --dir functions install --frozen-lockfile --ignore-scripts --registry=https://registry.npmjs.org/
pnpm --dir functions test
```

`test` najprv spustí TypeScript build a potom Node test runner nad
`test/verifier.test.mjs`. Testuje zostavený JavaScript; experimentálne stripovanie
TypeScriptu nie je potrebné. Lockfile `functions/pnpm-lock.yaml` zostáva zdrojom
presných verzií. Výstup `functions/lib/` ani `node_modules/` sa necommitujú.

## Implementované rozhrania

| Funkcia | Správanie v zdrojovom kóde |
| --- | --- |
| `verifyPlayPurchase` | Callable v `europe-west1`, vyžaduje Firebase Auth a vynucuje App Check. Overí balík, produkt, stav nákupu a väzbu na účet cez Google Play Developer API. |
| `handlePlayRtdn` | Pub/Sub handler témy `play-billing-rtdn` v `europe-west1`. Notifikácia je podnetom na opätovné overenie stavu cez Play API, nie dôkazom nároku. Zlyhané doručenie sa opakuje. |

[verifier.ts](src/verifier.ts) obsahuje doménovú logiku; [index.ts](src/index.ts)
adaptéry Play API, Firestore a exporty funkcií. Používa sa
`@googleapis/androidpublisher`, nie celý balík všetkých Google API.

Verifikátor akceptuje iba `sk.bizagent.bleskovafaktura` a tento katalóg:

| Produkt | Typ | Nárok po overení |
| --- | --- | --- |
| `blesk_credits_10` | Jednorazový | 10 kreditov |
| `blesk_credits_50` | Jednorazový | 50 kreditov |
| `blesk_credits_100` | Jednorazový | 100 kreditov |
| `blesk_premium_monthly` | Predplatné | Predplatiteľský stav podľa Play API |

Ceny sa overujú v Play Console a klient získava dostupné ceny cez Billing API;
náhradná cena v UI nepotvrdzuje konfiguráciu produktu v obchode.

## Vlastníctvo a idempotencia

- Klient odosiela `obfuscatedAccountId` odvodený cez SHA-256 z
  `blesk-play-account-v1:` a Firebase UID. Server ho porovná s hodnotou z Play API
  pred prvým pripísaním nároku.
- Token nákupu sa identifikuje SHA-256 hashom. Záznamy `billingTokens/{tokenHash}`
  a zmeny používateľského nároku sa zapisujú transakčne do pomenovanej databázy
  `ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d`.
  Klientom je priame čítanie a zápis billing tokenov zakázané cez Firestore rules.
- Opakovanie známeho nákupu tým istým používateľom nesmie pripísať nárok dvakrát.
  Cudzí účet, chýbajúca väzba alebo neznámy už spotrebovaný token sa odmietajú.
- Jednorazový produkt sa spotrebuje až po zápise nároku; opakovanie vie dokončiť
  predtým neúspešné spotrebovanie. Predplatné používa overenie stavu a acknowledge.
- Stav `PENDING` nie je zaplatený nákup. Klient čaká na dokončenie platby a
  serverové overenie; samotný callback nesmie zvýšiť kredity.
- RTDN testovacia správa nemení nároky. Refundácia alebo zmena predplatného sa
  posúdia podľa znovu overeného stavu Play.

Nákupné tokeny, poverenia ani obsah dokladov nepatria do logov alebo testovacích
výstupov. Detaily implementácie dátových tokov sú v
[architektúre](../docs/architecture.md).

## Hranice úspornej konfigurácie a vydania

Obe funkcie majú v kóde `minInstances: 0`, `maxInstances: 1`, `memory: "256MiB"`,
`cpu: "gcf_gen1"`, `concurrency: 1` a timeout 60 sekúnd. Toto je obmedzenie
kapacity, nie pevný cenový strop. Nevzťahuje sa na priame AI volania z Androidu.

Prípadné budúce nasadenie potrebuje samostatne vyriešený billing, oprávnenia
služobného účtu, Play produkty, RTDN a integračné testovanie. Tieto kroky sú pri
aktuálnom rozhodnutí blokované. [Release checklist](../docs/release-signing-and-smoke-test.md)
odlišuje lokálne dôkazy od zatiaľ neovereného produkčného účtovania a Play platieb.
