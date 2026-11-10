package de.riverty.buchungsservice.internal.service;

import de.riverty.buchungsservice.internal.domain.AusbuchungsGrund;

public record AusbuchungBefehl(AusbuchungsGrund grund, String bemerkung, String ausloeser, String clientRequestId) {
}
