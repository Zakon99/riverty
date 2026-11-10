package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import de.riverty.buchungsservice.internal.domain.Buchungsart;

public record SollbuchungBefehl(Buchungsart art, BigDecimal betrag, LocalDate valuta, String clientRequestId) {
}
