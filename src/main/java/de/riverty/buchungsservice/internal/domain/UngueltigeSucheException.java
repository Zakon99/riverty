package de.riverty.buchungsservice.internal.domain;

/** Eine Suche wurde ohne ein nicht leeres Suchkriterium aufgerufen (in Stufe 2: 400). */
public class UngueltigeSucheException extends FachlicheException {

    private static final long serialVersionUID = 1L;

    public UngueltigeSucheException(String message) {
        super(message);
    }
}
