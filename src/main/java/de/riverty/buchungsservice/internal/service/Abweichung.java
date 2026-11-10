package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;

/**
 * Eine gefundene Inkonsistenz. {@code buchungId} ist bei {@link Pruefung#SALDO} null; {@code differenz = ist − erwartet}.
 */
public record Abweichung(Pruefung pruefung, Long buchungId, BigDecimal erwartet, BigDecimal ist, BigDecimal differenz) {

    static Abweichung von(Pruefung pruefung, Long buchungId, BigDecimal erwartet, BigDecimal ist) {
        return new Abweichung(pruefung, buchungId, erwartet, ist, ist.subtract(erwartet));
    }
}
