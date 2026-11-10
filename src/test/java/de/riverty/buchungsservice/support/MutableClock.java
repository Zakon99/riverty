package de.riverty.buchungsservice.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Verstellbare Test-Uhr, standardmaessig in UTC. Startet bei {@link #START} und wird von Tests explizit weitergestellt.
 * Die Zone laesst sich fuer einzelne Tests umstellen ({@link #zoneSetzen(ZoneId)}); {@link #zuruecksetzen()} stellt
 * Zeitpunkt und Zone (UTC) wieder her.
 */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-10T09:00:00Z");

    private volatile Instant jetzt = START;
    private volatile ZoneId zone = ZoneOffset.UTC;

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId andereZone) {
        return Clock.fixed(jetzt, andereZone);
    }

    @Override
    public Instant instant() {
        return jetzt;
    }

    public synchronized void vorstellen(Duration dauer) {
        jetzt = jetzt.plus(dauer);
    }

    public synchronized void setzen(Instant zeitpunkt) {
        jetzt = zeitpunkt;
    }

    public synchronized void zoneSetzen(ZoneId neueZone) {
        zone = neueZone;
    }

    public synchronized void zuruecksetzen() {
        jetzt = START;
        zone = ZoneOffset.UTC;
    }
}
