package de.riverty.buchungsservice.internal.service;

import de.riverty.buchungsservice.internal.domain.StornoGrund;

public record StornoBefehl(StornoGrund grund, String bemerkung, String ausloeser, String clientRequestId) {
}
