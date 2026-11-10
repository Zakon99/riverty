package de.riverty.buchungsservice.internal.domain;

public class NichtGefundenException extends FachlicheException {

    private static final long serialVersionUID = 1L;

    public NichtGefundenException(String message) {
        super(message);
    }
}
