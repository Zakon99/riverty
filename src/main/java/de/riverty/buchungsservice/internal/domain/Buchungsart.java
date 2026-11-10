package de.riverty.buchungsservice.internal.domain;

import java.util.EnumSet;
import java.util.Set;

public enum Buchungsart {
    HAUPTFORDERUNG(true, 3),
    ZINSEN(true, 2),
    GEBUEHREN(true, 1),
    ZAHLUNG(false, 0),
    AUSBUCHUNG(false, 0),
    /** Steht auf der Gegenseite der stornierten Buchung. */
    STORNO(false, 0);

    private final boolean sollart;
    private final int tilgungsrang;

    Buchungsart(boolean sollart, int tilgungsrang) {
        this.sollart = sollart;
        this.tilgungsrang = tilgungsrang;
    }

    public boolean istSollart() {
        return sollart;
    }

    /**
     * Tilgungsreihenfolge nach § 367 BGB: Kosten (Gebühren) vor Zinsen vor Hauptforderung. Kleinerer Rang wird zuerst
     * bedient; nur für Sollarten relevant.
     */
    public int tilgungsrang() {
        return tilgungsrang;
    }

    public static Set<Buchungsart> sollarten() {
        return EnumSet.of(HAUPTFORDERUNG, ZINSEN, GEBUEHREN);
    }
}
