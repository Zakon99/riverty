package de.riverty.buchungsservice.internal.domain;

/**
 * Konflikt mit dem aktuellen Zustand: Doppelstorno, Storno eines Stornos, Idempotenz-Konflikt, parallele Änderung.
 */
public class KonfliktException extends FachlicheException {

    private static final long serialVersionUID = 1L;

    public KonfliktException(String message) {
        super(message);
    }

    public KonfliktException(String message, Throwable cause) {
        super(message, cause);
    }
}
