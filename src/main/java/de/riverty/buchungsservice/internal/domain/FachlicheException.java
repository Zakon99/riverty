package de.riverty.buchungsservice.internal.domain;

/**
 * Basis aller fachlichen Fehler. Die Unterklassen bestimmen die Fehlerkategorie (und in Stufe 2 den HTTP-Status).
 */
public abstract class FachlicheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected FachlicheException(String message) {
        super(message);
    }

    protected FachlicheException(String message, Throwable cause) {
        super(message, cause);
    }
}
