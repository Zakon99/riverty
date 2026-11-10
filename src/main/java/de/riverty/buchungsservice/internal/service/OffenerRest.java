package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;

import de.riverty.buchungsservice.internal.domain.Buchung;

/**
 * Noch nicht verrechneter Rest einer Buchung während einer Verteilung: offener Betrag eines Sollpostens bzw.
 * verfügbares Guthaben einer Haben-Buchung.
 */
final class OffenerRest {

    private final Buchung buchung;
    private BigDecimal betrag;

    OffenerRest(Buchung buchung, BigDecimal betrag) {
        this.buchung = buchung;
        this.betrag = betrag;
    }

    Buchung buchung() {
        return buchung;
    }

    BigDecimal betrag() {
        return betrag;
    }

    boolean istOffen() {
        return betrag.signum() > 0;
    }

    /** Mindert den Rest um höchstens den angegebenen Betrag und liefert den tatsächlich entnommenen Betrag. */
    BigDecimal entnehmen(BigDecimal hoechstens) {
        BigDecimal entnommen = betrag.min(hoechstens);
        betrag = betrag.subtract(entnommen);
        return entnommen;
    }
}
