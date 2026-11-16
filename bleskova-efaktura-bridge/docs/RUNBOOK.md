# Runbook

## Lokálny štart

```bash
npm ci && npm test && npm start
```

Health: `GET /health` bez tokenu. Mutácie potrebujú `Authorization: Bearer emu...`, `X-Firebase-AppCheck` a `X-Tenant-Id`. Tajomstvo je len lokálny HMAC z `.env.example`.

## Keď validátor spadne

Import originálu sa dá uložiť len ak XML prejde bezpečnostným parserom. Ak Java/Saxon nebeží, report je `UNAVAILABLE` a send aj issue ostanú zatvorené. Log nesmie obsahovať XML ani token. Hľadaj `correlationId`.

## Timeout poštára

Stav `UNKNOWN`, potom lookup. Nevytváraj druhý doklad a nemíňaj ďalšie číslo. Sandbox timeout je testovací scenár `X-Sandbox-Scenario: timeout` a funguje iba pri `local-sandbox`.

## Retencia

Originál sa drží podľa hashu. Zmazanie účtu nesmie sľúbiť okamžitý výmaz účtovného archívu. Právna lehota uchovania faktúr je oddelená od výmazu osobných údajov a v tomto module nie je tlačidlo „zmazať všetko“.

## Čoho sa nedotýkať pri release aplikácie

applicationId, upload keystore, Firebase projekt, existujúca Room schéma, kým nie je schválená migrácia z `docs/INTEGRATION.md`.
