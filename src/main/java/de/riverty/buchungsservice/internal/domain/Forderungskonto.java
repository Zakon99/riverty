package de.riverty.buchungsservice.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Forderungskonto mit genau einem, unveränderlichen Kreditor. Der Saldo (Σ Soll − Σ Haben) wird gespeichert und mit
 * jeder Buchung in derselben Transaktion fortgeschrieben; {@code @Version} erkennt konkurrierende Änderungen.
 */
@Entity
@Table(name = "forderungskonto")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Forderungskonto extends Entitaet {

    private String aktenzeichen;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kreditor_id", updatable = false)
    private Kreditor kreditor;

    @Column(precision = 15, scale = Betrag.NACHKOMMASTELLEN)
    private BigDecimal saldo = Betrag.NULLWERT;

    @Version
    private Long version;

    @OneToMany(mappedBy = "konto", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<KontoDebitor> debitoren = new ArrayList<>();

    /** Das Aktenzeichen wird nur getrimmt, Groß-/Kleinschreibung bleibt erhalten. */
    public static Forderungskonto eroeffnen(String aktenzeichen, Kreditor kreditor) {
        Forderungskonto konto = new Forderungskonto();
        konto.aktenzeichen = normalisiereAktenzeichen(aktenzeichen);
        konto.kreditor = kreditor;
        return konto;
    }

    public static String normalisiereAktenzeichen(String aktenzeichen) {
        return aktenzeichen.strip();
    }

    public KontoDebitor ordneZu(Debitor debitor, Instant zugeordnetAm, String zugeordnetVon) {
        KontoDebitor zuordnung = KontoDebitor.zuordnen(this, debitor, zugeordnetAm, zugeordnetVon);
        debitoren.add(zuordnung);
        return zuordnung;
    }

    public void buche(Buchung buchung) {
        saldo = saldo.add(buchung.saldoWirkung());
    }

    public List<KontoDebitor> getDebitoren() {
        return Collections.unmodifiableList(debitoren);
    }
}
