package de.riverty.buchungsservice.internal.persistence;

import java.math.BigDecimal;

/**
 * Netto verrechneter Betrag (Verrechnungen − Aufhebungen) einer Buchung, zusammen mit deren Buchungsbetrag.
 */
public record NettoVerrechnet(Long buchungId, BigDecimal betrag, BigDecimal netto) {
}
