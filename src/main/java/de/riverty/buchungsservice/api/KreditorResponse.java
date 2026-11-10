package de.riverty.buchungsservice.api;

import de.riverty.buchungsservice.internal.domain.Kreditor;

public record KreditorResponse(Long id, String kreditorennummer, String name) {

    static KreditorResponse von(Kreditor kreditor) {
        return new KreditorResponse(kreditor.getId(), kreditor.getKreditorennummer(), kreditor.getName());
    }
}
