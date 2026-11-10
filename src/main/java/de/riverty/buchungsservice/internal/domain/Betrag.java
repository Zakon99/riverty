package de.riverty.buchungsservice.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.persistence.Embeddable;

/**
 * Positiver EUR-Betrag mit zwei Nachkommastellen (HALF_UP) und höchstens 13 Vorkommastellen (passend zu
 * {@code NUMERIC(15,2)}). Die Richtung (Soll/Haben) ergibt sich aus der Buchungsart.
 */
@Embeddable
public record Betrag(BigDecimal wert) {

    public static final int NACHKOMMASTELLEN = 2;
    public static final int VORKOMMASTELLEN = 13;

    /** 0.00 als Ausgangswert für Summen und Salden (selbst kein gültiger Betrag). */
    public static final BigDecimal NULLWERT = BigDecimal.ZERO.setScale(NACHKOMMASTELLEN);

    public Betrag {
        if (wert == null) {
            throw new UngueltigerBetragException("Betrag fehlt.");
        }
        wert = wert.setScale(NACHKOMMASTELLEN, RoundingMode.HALF_UP);
        if (wert.signum() <= 0) {
            throw new UngueltigerBetragException(
                    "Betrag muss nach Rundung auf zwei Nachkommastellen größer als 0 sein, war " + wert.toPlainString() + ".");
        }
        if (wert.precision() - wert.scale() > VORKOMMASTELLEN) {
            throw new UngueltigerBetragException("Betrag darf höchstens " + VORKOMMASTELLEN
                    + " Vorkommastellen haben, war " + wert.toPlainString() + ".");
        }
    }

    public static Betrag of(BigDecimal wert) {
        return new Betrag(wert);
    }

    public static Betrag of(String wert) {
        if (wert == null) {
            return new Betrag(null);
        }
        try {
            return new Betrag(new BigDecimal(wert.strip()));
        } catch (NumberFormatException e) {
            throw new UngueltigerBetragException("Betrag ist keine gültige Zahl: " + wert);
        }
    }
}
