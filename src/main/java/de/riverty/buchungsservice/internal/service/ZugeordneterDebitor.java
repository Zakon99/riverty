package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.Instant;

import de.riverty.buchungsservice.internal.domain.Debitor;

/**
 * Ein dem Konto zugeordneter Debitor mit Zuordnungsdaten und seinem Guthaben auf diesem Konto.
 * <p>
 * Der Debitor selbst ist geladen. Von seinen Relationen darf außerhalb der Service-Transaktion
 * ({@code open-in-view: false}) nur die Id gelesen werden (Lazy-Proxies).
 */
public record ZugeordneterDebitor(Debitor debitor, Instant zugeordnetAm, String zugeordnetVon, BigDecimal guthaben) {
}
