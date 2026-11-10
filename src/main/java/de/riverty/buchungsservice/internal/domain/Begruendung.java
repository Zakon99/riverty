package de.riverty.buchungsservice.internal.domain;

/**
 * Pflichtangaben zu Storno und Ausbuchung: Grund ({@link StornoGrund} bzw. {@link AusbuchungsGrund}), optionale
 * Bemerkung, Auslöser. Grund und Auslöser sind Pflicht; fehlen sie, ist die Fachregel verletzt.
 */
public record Begruendung(Grund grund, String bemerkung, String ausloeser) {

    public Begruendung {
        if (grund == null) {
            throw new FachlicheRegelVerletztException("Ein Grund ist Pflicht.");
        }
        if (ausloeser == null || ausloeser.isBlank()) {
            throw new FachlicheRegelVerletztException("Ein Auslöser ist Pflicht.");
        }
    }
}
