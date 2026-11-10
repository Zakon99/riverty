package de.riverty.buchungsservice.api;

import java.math.BigDecimal;
import java.util.List;

import de.riverty.buchungsservice.internal.service.KontoUebersicht;

public record KontoResponse(Long id, String aktenzeichen, BigDecimal saldo, KreditorResponse kreditor,
        List<Long> debitorIds) {

    static KontoResponse von(KontoUebersicht konto) {
        return new KontoResponse(konto.id(), konto.aktenzeichen(), konto.saldo(), KreditorResponse.von(konto.kreditor()),
                konto.debitorIds());
    }
}
