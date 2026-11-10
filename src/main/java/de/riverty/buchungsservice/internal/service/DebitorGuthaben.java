package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;

public record DebitorGuthaben(Long debitorId, BigDecimal guthaben) {
}
