package de.riverty.buchungsservice.internal.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Zuordnung eines Debitors zu einem Forderungskonto (n:m mit Zeitpunkt und Bearbeiter).
 */
@Entity
@Table(name = "konto_debitor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KontoDebitor extends Entitaet {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "konto_id")
    private Forderungskonto konto;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debitor_id")
    private Debitor debitor;

    private Instant zugeordnetAm;
    private String zugeordnetVon;

    static KontoDebitor zuordnen(Forderungskonto konto, Debitor debitor, Instant zugeordnetAm, String zugeordnetVon) {
        KontoDebitor zuordnung = new KontoDebitor();
        zuordnung.konto = konto;
        zuordnung.debitor = debitor;
        zuordnung.zugeordnetAm = zugeordnetAm;
        zuordnung.zugeordnetVon = zugeordnetVon;
        return zuordnung;
    }
}
