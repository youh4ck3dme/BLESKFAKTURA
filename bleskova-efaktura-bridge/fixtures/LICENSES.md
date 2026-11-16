# Pôvod fixture a artefaktov

Overené 2026-11-16.

- `fixtures/peppol-official/*.xml` pochádzajú z balíka BIS-Billing3-Examples.zip na https://docs.peppol.eu/poacc/billing/3.0/files/BIS-Billing3-Examples.zip (May 2026 Release, súbory datované 2026-05-21). Sú to syntetické príklady OpenPeppol, nie doklady používateľa.
- `packages/validation/rules/peppol/PEPPOL-EN16931-UBL.sch` sha256 `62e5b67892f12755352d78b06f63229a02cc2eccc748677c56efbc8dbcb336e3`, komentár v súbore: May 2026 release 3.0.21.
- `packages/validation/rules/peppol/CEN-EN16931-UBL.sch` sha256 `268d4f7a2688676695e6c69cba6fba69a6802604fee12cb544a6b30ff09555a3`, Schematron 1.3.16, EUPL-1.2, last update 2026-04-10.
- UBL 2.1 xsdrt je z https://docs.oasis-open.org/ubl/os-UBL-2.1/xsdrt/ (OASIS, 4. november 2013).
- SK XLSX `2026.10.29_Peppol_Bis3_v1_13.xlsx` sha256 `002a65e6da9debd9af5131e213d6ad1bbc2adc47641b62a9311924ecc7b4cfc9` je pripnutý referenčný súbor FS SR. Ako Schematron sa nespúšťa.
- Saxon-HE 12.9 je MPL 2.0, SchXslt 1.10.1 je MIT, xmlresolver 5.3.3 je Apache-2.0.
- `fixtures/synthetic/base-example.outbound.json` je kanonický JSON z `inspectUbl` oficiálneho `base-example.xml` v smere OUTBOUND. Nie je to druhý právny doklad.
