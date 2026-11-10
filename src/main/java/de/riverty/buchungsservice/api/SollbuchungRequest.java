package de.riverty.buchungsservice.api;

import java.math.BigDecimal;
import java.time.LocalDate;

import de.riverty.buchungsservice.internal.service.SollbuchungBefehl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Rundung und Obergrenze des Betrags prüft der Service (400 über {@code UngueltigerBetragException}). */
public record SollbuchungRequest(
        @NotNull Sollart art,
        @NotNull @Positive BigDecimal betrag,
        @NotNull @Pattern(regexp = "EUR") String waehrung,
        @NotNull LocalDate valuta,
        @NotBlank @Size(max = 100) String clientRequestId) {

    SollbuchungBefehl alsBefehl() {
        return new SollbuchungBefehl(art.buchungsart(), betrag, valuta, clientRequestId);
    }
}
