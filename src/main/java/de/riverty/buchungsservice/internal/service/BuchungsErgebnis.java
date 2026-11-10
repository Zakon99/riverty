package de.riverty.buchungsservice.internal.service;

import de.riverty.buchungsservice.internal.domain.Buchung;

/**
 * Ergebnis einer schreibenden Buchungsanfrage; {@code neuAngelegt == false} kennzeichnet ein idempotentes Replay.
 */
public record BuchungsErgebnis(Buchung buchung, boolean neuAngelegt) {
}
