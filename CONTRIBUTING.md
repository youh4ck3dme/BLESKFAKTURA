# Príspevky do Bleskovej Faktúry

Začni [README](README.md), [lokálnym overením](docs/local-verification.md) a
[aktuálnym stavom](docs/project-status.md). Používaj Gradle wrapper a uzamknutý pnpm
lockfile; verzie nástrojov sú v CI. Neprepisuj rozpracovanú prácu iného prispievateľa.

Opravu priprav v samostatnej vetve ako malý reviewovateľný PR. Popíš konkrétnu chybu,
výsledné správanie, relevantné testy a neoverené scenáre. Zmeny správania over regresným
testom. Dokumentačná oprava nemusí pridávať Android test.

Pred odovzdaním spusti `python3 scripts/check-docs.py` a `git diff --check`.
Android a backendové príkazy nájdeš v [lokálnom návode](docs/local-verification.md).
Nespúšťaj súbežné Gradle buildy v tom istom prostredí.

Reprodukcie a testy používajú syntetické doklady a izolované Firebase emulátory.
Do issues, logov ani PR nepatria osobné údaje, nákupné tokeny, SDK payloady či debug
secrety. Konfigurácie a podpisové kľúče zostávajú mimo repozitára.

Zmena kódu nie je súhlasom na aktiváciu platených služieb, nasadenie, vydanie do Play,
zmenu podpisovej identity alebo oslabenie App Check. Tieto kroky rieš samostatne
podľa [release návodu](docs/release-signing-and-smoke-test.md).
