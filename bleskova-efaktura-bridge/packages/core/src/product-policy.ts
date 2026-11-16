/**
 * Product policy is versioned separately from technical validation.
 * It is not a legal opinion and it does not force every document through Peppol.
 *
 * Verified 2026-11-16 against:
 * - https://static.slov-lex.sk/static/SK/ZZ/2004/222/20270101.html §85o
 * - https://www.financnasprava.sk/sk/podnikatelia/dane/dan-z-pridanej-hodnoty/e-faktura
 *
 * §85o (znenie účinné 1.1.2027) viaže elektronickú faktúru na konkrétne plnenie
 * platiteľa voči osobe podľa odseku 3, s výnimkami (oslobodené plnenia, zjednodušená
 * faktúra, SIS, vojenské spravodajstvo, utajované skutočnosti) a s prechodným
 * režimom pre rok 2026. Samotný paragraf nepomenúva Peppol; odkazuje na technickú
 * normu a osobitný predpis. Informačná stránka FS opisuje doručovanie cez
 * certifikovaných poštárov siete Peppol (§76a). Tento modul túto právnu vetvu
 * neautomatizuje.
 */
export const PRODUCT_POLICY_VERSION = "2026-11-16.sk-efaktura.1";

export const PRODUCT_POLICY = {
  version: PRODUCT_POLICY_VERSION,
  legalScopeEncoded: false,
  forcesAllUserInvoicesThroughPeppol: false,
  productionReportingClaimed: false,
} as const;
