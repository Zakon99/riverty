package de.riverty.buchungsservice.api;

import java.net.URI;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Kategorie einer Fehlerantwort: bestimmt {@code type} (URN) und {@code title} des ProblemDetails sowie den
 * {@code detail}-Text, falls die Ausnahme keinen liefert.
 */
enum Fehlerart {
    VALIDIERUNG("validierung", "Ungültige Anfrage", "Die Anfrage enthält ungültige oder fehlende Angaben."),
    NICHT_GEFUNDEN("nicht-gefunden", "Nicht gefunden", "Die angeforderte Ressource existiert nicht."),
    DUPLIKAT("duplikat", "Bereits vorhanden", "Der Datensatz existiert bereits."),
    KONFLIKT("konflikt", "Konflikt mit dem aktuellen Zustand", "Die Anfrage steht im Konflikt mit dem aktuellen Zustand."),
    FACHREGEL("fachregel", "Fachliche Regel verletzt", "Die Anfrage verletzt eine fachliche Regel."),
    /** Übrige Client-Fehler, etwa falsche HTTP-Methode oder nicht unterstützter Content-Type. */
    ANFRAGE("anfrage", "Anfrage nicht verarbeitbar", "Die Anfrage kann nicht verarbeitet werden."),
    INTERN("intern", "Interner Fehler", "Ein unerwarteter Fehler ist aufgetreten.");

    private final URI typ;
    private final String titel;
    private final String standardDetail;

    Fehlerart(String kennung, String titel, String standardDetail) {
        this.typ = URI.create("urn:buchungsservice:" + kennung);
        this.titel = titel;
        this.standardDetail = standardDetail;
    }

    /** Kategorie aus dem Status; 409 ohne nähere Angabe ist ein Konflikt (Duplikate werden explizit gekennzeichnet). */
    static Fehlerart fuer(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> VALIDIERUNG;
            case 404 -> NICHT_GEFUNDEN;
            case 409 -> KONFLIKT;
            case 422 -> FACHREGEL;
            default -> status.is5xxServerError() ? INTERN : ANFRAGE;
        };
    }

    void kennzeichnen(ProblemDetail problem) {
        problem.setType(typ);
        problem.setTitle(titel);
        if (problem.getDetail() == null) {
            problem.setDetail(standardDetail);
        }
    }
}
