package com.example.data

/**
 * NEAPLIKOVANÉ. Existujúca AppDatabase zostáva version = 1.
 * Nové tabuľky nesmú zredukovať XML na totalAmount a nesmú prepisovať invoices.
 *
 * Migration 1 -> 2:
 *   CREATE TABLE efaktura_documents (
 *     id TEXT NOT NULL PRIMARY KEY,
 *     tenantId TEXT NOT NULL,
 *     direction TEXT NOT NULL,
 *     kind TEXT,
 *     number TEXT,
 *     sha256 TEXT NOT NULL,
 *     lifecycle TEXT NOT NULL,
 *     validationStatus TEXT NOT NULL,
 *     deliveryStatus TEXT NOT NULL,
 *     originalPath TEXT NOT NULL,
 *     version INTEGER NOT NULL
 *   );
 *   CREATE TABLE efaktura_notes (
 *     id TEXT NOT NULL PRIMARY KEY,
 *     documentId TEXT NOT NULL,
 *     text TEXT NOT NULL
 *   );
 * Originál ostáva súbor/blob adresovaný sha256. Poznámka je v efaktura_notes.
 *
 * Rollback 2 -> 1: DROP TABLE efaktura_notes; DROP TABLE efaktura_documents;
 * iba ak nové tabuľky ešte nie sú účtovný archív. Tabuľku invoices nemeň a neresetuj.
 * Strata XML nie je prípustný rollback.
 */
object EfakturaRoomPlan
