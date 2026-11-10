package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.util.List;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Kreditor;

/**
 * Lesemodell der Kontoansicht aus einer Transaktion: Konto, Kreditor, zugeordnete Debitoren mit Guthaben und die
 * Historie (Valuta, Buchungszeit, Id aufsteigend).
 * <p>
 * Kreditor und Debitoren sind vollständig geladen. Von den Relationen der Buchungen ({@code kreditor}, {@code debitor},
 * {@code stornoVon}) darf außerhalb der Service-Transaktion ({@code open-in-view: false}) nur die Id gelesen werden;
 * es sind Lazy-Proxies.
 */
public record Kontoansicht(Long id, String aktenzeichen, BigDecimal saldo, Kreditor kreditor,
        List<ZugeordneterDebitor> debitoren, List<Buchung> buchungen) {
}
