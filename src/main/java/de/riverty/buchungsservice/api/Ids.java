package de.riverty.buchungsservice.api;

import de.riverty.buchungsservice.internal.domain.Entitaet;

/**
 * Id einer optionalen Referenz; liest nur die Id und lädt dadurch keine Lazy-Referenz nach.
 * <p>
 * Die Response-Mapper laufen außerhalb der Service-Transaktion ({@code open-in-view: false}). Von Relationen einer
 * Entität (z. B. {@code Buchung.kreditor}, {@code Buchung.stornoVon}) dürfen dort nur Ids gelesen werden: Es sind
 * Lazy-Proxies, jeder andere Zugriff endet in einer {@code LazyInitializationException}.
 */
final class Ids {

    private Ids() {
    }

    static Long von(Entitaet referenz) {
        return referenz == null ? null : referenz.getId();
    }
}
