package de.riverty.buchungsservice.api;

import java.time.LocalDate;

import de.riverty.buchungsservice.internal.domain.Debitor;

public record DebitorResponse(Long id, String vorname, String nachname, LocalDate geburtsdatum, String plz) {

    static DebitorResponse von(Debitor debitor) {
        return new DebitorResponse(debitor.getId(), debitor.getVorname(), debitor.getNachname(),
                debitor.getGeburtsdatum(), debitor.getPlz());
    }
}
