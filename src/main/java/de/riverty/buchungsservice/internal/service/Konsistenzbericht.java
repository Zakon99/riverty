package de.riverty.buchungsservice.internal.service;

import java.util.List;

public record Konsistenzbericht(KonsistenzStatus status, List<Abweichung> abweichungen) {
}
