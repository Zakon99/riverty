package de.riverty.buchungsservice.internal.domain;

import java.util.Objects;

/**
 * Idempotenzmerkmale einer schreibenden Anfrage: vom Client vergebene Id und Hash über die normalisierten Anfragefelder.
 */
public record AnfrageKennung(String clientRequestId, String requestHash) {

    public AnfrageKennung {
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(requestHash, "requestHash");
    }

    public boolean gleicherInhaltWie(Buchung buchung) {
        return requestHash.equals(buchung.getRequestHash());
    }
}
