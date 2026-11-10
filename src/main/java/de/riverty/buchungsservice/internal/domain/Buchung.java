package de.riverty.buchungsservice.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * Unveränderliche Buchung auf einem Forderungskonto (append-only). Der Betrag ist immer positiv, die Richtung ergibt
 * sich aus der Buchungsart; ein Storno steht auf der Gegenseite der stornierten Buchung.
 */
@Entity
@Immutable
@Table(name = "buchung")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Buchung extends Entitaet {

    /** Historienreihenfolge: Valuta, dann Buchungszeit, dann Id. */
    public static final Comparator<Buchung> CHRONOLOGISCH = Comparator.comparing(Buchung::getValuta)
            .thenComparing(Buchung::getBuchungszeit)
            .thenComparing(Buchung::getId);

    /** Reihenfolge, in der offene Posten nach § 367 BGB bedient werden: Art, dann chronologisch. */
    public static final Comparator<Buchung> TILGUNGSREIHENFOLGE =
            Comparator.comparingInt((Buchung buchung) -> buchung.getArt().tilgungsrang()).thenComparing(CHRONOLOGISCH);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "konto_id")
    private Forderungskonto konto;

    @Enumerated(EnumType.STRING)
    private Buchungsart art;

    @Embedded
    @AttributeOverride(name = "wert", column = @Column(name = "betrag", precision = 15, scale = Betrag.NACHKOMMASTELLEN))
    private Betrag betrag;

    private LocalDate valuta;
    private Instant buchungszeit;
    private String clientRequestId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kreditor_id")
    private Kreditor kreditor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debitor_id")
    private Debitor debitor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storno_von_id")
    private Buchung stornoVon;

    private String grund;
    private String bemerkung;
    private String ausloeser;

    public static Buchung sollbuchung(Forderungskonto konto, Buchungsart art, Betrag betrag, LocalDate valuta,
            Instant buchungszeit, AnfrageKennung anfrage) {
        if (art == null || !art.istSollart()) {
            throw new FachlicheRegelVerletztException(
                    "Eine Sollbuchung muss die Art HAUPTFORDERUNG, ZINSEN oder GEBUEHREN haben, war " + art + ".");
        }
        Buchung buchung = neu(konto, art, betrag, valuta, buchungszeit, anfrage);
        buchung.kreditor = konto.getKreditor();
        return buchung;
    }

    public static Buchung zahlung(Forderungskonto konto, Debitor debitor, Betrag betrag, LocalDate valuta,
            Instant buchungszeit, AnfrageKennung anfrage) {
        Buchung buchung = neu(konto, Buchungsart.ZAHLUNG, betrag, valuta, buchungszeit, anfrage);
        buchung.debitor = debitor;
        return buchung;
    }

    /** Die Valuta einer Ausbuchung ist das UTC-Datum der Buchungszeit. */
    public static Buchung ausbuchung(Forderungskonto konto, Betrag betrag, Begruendung begruendung,
            Instant buchungszeit, AnfrageKennung anfrage) {
        LocalDate valuta = LocalDate.ofInstant(buchungszeit, ZoneOffset.UTC);
        Buchung buchung = neu(konto, Buchungsart.AUSBUCHUNG, betrag, valuta, buchungszeit, anfrage);
        buchung.begruenden(begruendung);
        return buchung;
    }

    /** Storno mit gleichem Betrag und gleicher Valuta wie diese Buchung; diese Buchung bleibt unverändert. */
    public Buchung storniere(Begruendung begruendung, Instant buchungszeit, AnfrageKennung anfrage) {
        if (art == Buchungsart.STORNO) {
            throw new KonfliktException("Buchung " + getId() + " ist selbst ein Storno und kann nicht storniert werden.");
        }
        Buchung storno = neu(konto, Buchungsart.STORNO, betrag, valuta, buchungszeit, anfrage);
        storno.stornoVon = this;
        storno.begruenden(begruendung);
        return storno;
    }

    /** Wirkung auf den Saldo: Soll positiv, Haben negativ, Storno mit umgekehrtem Vorzeichen des Originals. */
    public BigDecimal saldoWirkung() {
        if (art == Buchungsart.STORNO) {
            return stornoVon.saldoWirkung().negate();
        }
        return art.istSollart() ? betrag.wert() : betrag.wert().negate();
    }

    public boolean gehoertZu(Forderungskonto anderesKonto) {
        return konto.getId().equals(anderesKonto.getId());
    }

    private static Buchung neu(Forderungskonto konto, Buchungsart art, Betrag betrag, LocalDate valuta,
            Instant buchungszeit, AnfrageKennung anfrage) {
        Buchung buchung = new Buchung();
        buchung.konto = konto;
        buchung.art = art;
        buchung.betrag = betrag;
        buchung.valuta = valuta;
        buchung.buchungszeit = buchungszeit;
        buchung.clientRequestId = anfrage.clientRequestId();
        buchung.requestHash = anfrage.requestHash();
        return buchung;
    }

    private void begruenden(Begruendung begruendung) {
        grund = begruendung.grund().name();
        bemerkung = begruendung.bemerkung();
        ausloeser = begruendung.ausloeser();
    }
}
