package de.riverty.buchungsservice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.service.ZugeordneterDebitor;

public record ZugeordneterDebitorResponse(Long id, String vorname, String nachname, LocalDate geburtsdatum, String plz,
        Instant zugeordnetAm, String zugeordnetVon, BigDecimal guthaben) {

    static ZugeordneterDebitorResponse von(ZugeordneterDebitor zugeordnet) {
        Debitor debitor = zugeordnet.debitor();
        return new ZugeordneterDebitorResponse(debitor.getId(), debitor.getVorname(), debitor.getNachname(),
                debitor.getGeburtsdatum(), debitor.getPlz(), zugeordnet.zugeordnetAm(), zugeordnet.zugeordnetVon(),
                zugeordnet.guthaben());
    }
}
