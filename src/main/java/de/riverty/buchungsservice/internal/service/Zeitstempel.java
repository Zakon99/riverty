package de.riverty.buchungsservice.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Zentrale Quelle für fachliche Zeitstempel (buchungszeit, zugeordnetAm, Verrechnungszeitpunkt). Gekürzt auf
 * Mikrosekunden, der Genauigkeit von {@code TIMESTAMP WITH TIME ZONE}; so stimmen das Objekt im Speicher und der
 * gespeicherte Wert überein.
 */
final class Zeitstempel {

    private Zeitstempel() {
    }

    static Instant jetzt(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
