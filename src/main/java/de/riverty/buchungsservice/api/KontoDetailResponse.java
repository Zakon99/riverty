package de.riverty.buchungsservice.api;

import java.math.BigDecimal;
import java.util.List;

import de.riverty.buchungsservice.internal.service.Kontoansicht;

public record KontoDetailResponse(Long id, String aktenzeichen, BigDecimal saldo, KreditorResponse kreditor,
        List<ZugeordneterDebitorResponse> debitoren, List<BuchungResponse> buchungen) {

    static KontoDetailResponse von(Kontoansicht ansicht) {
        return new KontoDetailResponse(ansicht.id(), ansicht.aktenzeichen(), ansicht.saldo(),
                KreditorResponse.von(ansicht.kreditor()),
                ansicht.debitoren().stream().map(ZugeordneterDebitorResponse::von).toList(),
                ansicht.buchungen().stream().map(BuchungResponse::von).toList());
    }
}
