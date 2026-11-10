package de.riverty.buchungsservice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.service.OffenerPosten;

public record OffenerPostenResponse(Long buchungId, Buchungsart art, BigDecimal betrag, BigDecimal offen,
        LocalDate valuta, Instant buchungszeit) {

    static OffenerPostenResponse von(OffenerPosten posten) {
        return new OffenerPostenResponse(posten.buchungId(), posten.art(), posten.betrag(), posten.offen(),
                posten.valuta(), posten.buchungszeit());
    }
}
