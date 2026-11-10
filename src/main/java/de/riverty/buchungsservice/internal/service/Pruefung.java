package de.riverty.buchungsservice.internal.service;

public enum Pruefung {
    /** Gespeicherter Saldo weicht vom Saldo aus allen Buchungen ab. */
    SALDO,
    /** Netto verrechneter Betrag einer Sollbuchung übersteigt ihren Betrag. */
    POSTEN_UEBERBEZAHLT,
    /** Netto verrechneter Betrag einer Zahlung oder Ausbuchung übersteigt ihren Betrag. */
    HABEN_UEBERVERTEILT,
    /** Netto verrechneter Betrag einer Buchung ist negativ (mehr aufgehoben als verrechnet). */
    VERRECHNUNG_NEGATIV
}
