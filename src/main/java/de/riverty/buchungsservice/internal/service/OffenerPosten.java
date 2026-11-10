package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import de.riverty.buchungsservice.internal.domain.Buchungsart;

public record OffenerPosten(Long buchungId, Buchungsart art, BigDecimal betrag, BigDecimal offen, LocalDate valuta,
        Instant buchungszeit) {
}
