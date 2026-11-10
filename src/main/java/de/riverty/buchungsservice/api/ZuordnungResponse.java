package de.riverty.buchungsservice.api;

import java.time.Instant;

import de.riverty.buchungsservice.internal.domain.KontoDebitor;

public record ZuordnungResponse(Long kontoId, Long debitorId, Instant zugeordnetAm, String zugeordnetVon) {

    static ZuordnungResponse von(KontoDebitor zuordnung) {
        return new ZuordnungResponse(zuordnung.getKonto().getId(), zuordnung.getDebitor().getId(),
                zuordnung.getZugeordnetAm(), zuordnung.getZugeordnetVon());
    }
}
