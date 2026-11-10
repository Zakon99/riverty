package de.riverty.buchungsservice.internal.domain;

public class UngueltigerBetragException extends FachlicheException {

    private static final long serialVersionUID = 1L;

    public UngueltigerBetragException(String message) {
        super(message);
    }
}
