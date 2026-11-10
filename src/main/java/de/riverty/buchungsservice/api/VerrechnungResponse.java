package de.riverty.buchungsservice.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.domain.VerrechnungsArt;

/** {@code aufhebungVonId} und {@code ausgeloestDurchId} gibt es nur bei Aufhebungen. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerrechnungResponse(Long id, VerrechnungsArt art, Long habenBuchungId, Long sollBuchungId,
        BigDecimal betrag, Instant zeitpunkt, Long aufhebungVonId, Long ausgeloestDurchId) {

    static VerrechnungResponse von(Verrechnung verrechnung) {
        return new VerrechnungResponse(verrechnung.getId(), verrechnung.getArt(), verrechnung.getHabenBuchung().getId(),
                verrechnung.getSollBuchung().getId(), verrechnung.getBetrag().wert(), verrechnung.getZeitpunkt(),
                Ids.von(verrechnung.getAufhebungVon()), Ids.von(verrechnung.getAusgeloestDurch()));
    }
}
