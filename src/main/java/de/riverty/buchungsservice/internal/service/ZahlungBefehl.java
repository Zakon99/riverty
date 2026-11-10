package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ZahlungBefehl(Long debitorId, BigDecimal betrag, LocalDate valuta, String clientRequestId) {
}
