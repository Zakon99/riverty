package de.riverty.buchungsservice.api;

import java.util.List;

import de.riverty.buchungsservice.internal.service.KonsistenzStatus;
import de.riverty.buchungsservice.internal.service.Konsistenzbericht;

public record KonsistenzResponse(KonsistenzStatus status, List<AbweichungResponse> abweichungen) {

    static KonsistenzResponse von(Konsistenzbericht bericht) {
        return new KonsistenzResponse(bericht.status(),
                bericht.abweichungen().stream().map(AbweichungResponse::von).toList());
    }
}
