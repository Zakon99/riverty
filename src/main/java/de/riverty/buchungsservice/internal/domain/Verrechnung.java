package de.riverty.buchungsservice.internal.domain;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Unveränderliche Zuordnung eines Teilbetrags einer Haben-Buchung (Zahlung, Ausbuchung) zu einer Sollbuchung.
 * Rückgängig gemacht wird sie nie durch Löschen, sondern durch eine AUFHEBUNG über denselben Betrag.
 */
@Entity
@Immutable
@Table(name = "verrechnung")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Verrechnung extends Entitaet {

    @Enumerated(EnumType.STRING)
    private VerrechnungsArt art;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "haben_buchung_id")
    private Buchung habenBuchung;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "soll_buchung_id")
    private Buchung sollBuchung;

    @Embedded
    @AttributeOverride(name = "wert", column = @Column(name = "betrag", precision = 15, scale = Betrag.NACHKOMMASTELLEN))
    private Betrag betrag;

    private Instant zeitpunkt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aufhebung_von_id")
    private Verrechnung aufhebungVon;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ausgeloest_durch_id")
    private Buchung ausgeloestDurch;

    public static Verrechnung verrechne(Buchung habenBuchung, Buchung sollBuchung, Betrag betrag, Instant zeitpunkt) {
        return neu(VerrechnungsArt.VERRECHNUNG, habenBuchung, sollBuchung, betrag, zeitpunkt);
    }

    /** Hebt diese Verrechnung auf: gleiche Soll-/Haben-Buchung, gleicher Betrag, ausgelöst durch den Storno. */
    public Verrechnung hebeAuf(Buchung storno, Instant zeitpunkt) {
        Verrechnung aufhebung = neu(VerrechnungsArt.AUFHEBUNG, habenBuchung, sollBuchung, betrag, zeitpunkt);
        aufhebung.aufhebungVon = this;
        aufhebung.ausgeloestDurch = storno;
        return aufhebung;
    }

    private static Verrechnung neu(VerrechnungsArt art, Buchung habenBuchung, Buchung sollBuchung, Betrag betrag,
            Instant zeitpunkt) {
        Verrechnung verrechnung = new Verrechnung();
        verrechnung.art = art;
        verrechnung.habenBuchung = habenBuchung;
        verrechnung.sollBuchung = sollBuchung;
        verrechnung.betrag = betrag;
        verrechnung.zeitpunkt = zeitpunkt;
        return verrechnung;
    }
}
