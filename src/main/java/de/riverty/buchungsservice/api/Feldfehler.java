package de.riverty.buchungsservice.api;

/** Ein ungültiges Feld eines Requests, Teil des ProblemDetails bei 400. */
public record Feldfehler(String feld, String meldung) {
}
