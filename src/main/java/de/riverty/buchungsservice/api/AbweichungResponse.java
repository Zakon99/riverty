package de.riverty.buchungsservice.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import de.riverty.buchungsservice.internal.service.Abweichung;
import de.riverty.buchungsservice.internal.service.Pruefung;

/** {@code buchungId} fehlt bei der Saldo-Prüfung; {@code differenz = ist - erwartet}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AbweichungResponse(Pruefung pruefung, Long buchungId, BigDecimal erwartet, BigDecimal ist,
        BigDecimal differenz) {

    static AbweichungResponse von(Abweichung abweichung) {
        return new AbweichungResponse(abweichung.pruefung(), abweichung.buchungId(), abweichung.erwartet(),
                abweichung.ist(), abweichung.differenz());
    }
}
