package de.riverty.buchungsservice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;

/** Felder, die für die Buchungsart nicht gelten (z. B. {@code debitorId} bei Sollbuchungen), werden weggelassen. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuchungResponse(Long id, Long kontoId, Buchungsart art, BigDecimal betrag, LocalDate valuta,
        Instant buchungszeit, String clientRequestId, Long kreditorId, Long debitorId, Long stornoVonId, String grund,
        String bemerkung, String ausloeser) {

    static BuchungResponse von(Buchung buchung) {
        return new BuchungResponse(buchung.getId(), buchung.getKonto().getId(), buchung.getArt(),
                buchung.getBetrag().wert(), buchung.getValuta(), buchung.getBuchungszeit(), buchung.getClientRequestId(),
                Ids.von(buchung.getKreditor()), Ids.von(buchung.getDebitor()), Ids.von(buchung.getStornoVon()),
                buchung.getGrund(), buchung.getBemerkung(), buchung.getAusloeser());
    }
}
