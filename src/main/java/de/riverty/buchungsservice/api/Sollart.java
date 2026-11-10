package de.riverty.buchungsservice.api;

import de.riverty.buchungsservice.internal.domain.Buchungsart;

/** Zulässige Arten einer Sollbuchung; andere Werte lehnt die API bereits beim Lesen des Requests ab (400). */
public enum Sollart {
    HAUPTFORDERUNG, ZINSEN, GEBUEHREN;

    Buchungsart buchungsart() {
        return Buchungsart.valueOf(name());
    }
}
