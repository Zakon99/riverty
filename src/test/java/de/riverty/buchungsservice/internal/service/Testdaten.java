package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import de.riverty.buchungsservice.internal.domain.AusbuchungsGrund;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.StornoGrund;
import de.riverty.buchungsservice.support.MutableClock;

/**
 * Erzeugt Testdaten ueber die Services. Jede Buchung erhaelt eine eindeutige clientRequestId,
 * nach jeder Buchung wird die Clock um eine Minute weitergestellt (streng steigende Buchungszeiten).
 */
final class Testdaten {

    static final LocalDate VALUTA_HF = LocalDate.of(2026, 1, 10);
    static final LocalDate VALUTA_ZINSEN = LocalDate.of(2026, 2, 1);
    static final LocalDate VALUTA_GEB = LocalDate.of(2026, 2, 15);
    static final LocalDate VALUTA_ZAHLUNG = LocalDate.of(2026, 3, 1);
    static final LocalDate GEBURTSDATUM = LocalDate.of(1980, 1, 1);
    static final Duration SCHRITT = Duration.ofMinutes(1);

    private final KreditorService kreditorService;
    private final DebitorService debitorService;
    private final KontoService kontoService;
    private final BuchungService buchungService;
    private final MutableClock clock;
    private int zaehler;

    Testdaten(KreditorService kreditorService, DebitorService debitorService, KontoService kontoService,
            BuchungService buchungService, MutableClock clock) {
        this.kreditorService = kreditorService;
        this.debitorService = debitorService;
        this.kontoService = kontoService;
        this.buchungService = buchungService;
        this.clock = clock;
    }

    String naechsteRequestId() {
        zaehler++;
        return "req-" + zaehler;
    }

    Kreditor kreditor(String kreditorennummer) {
        return kreditorService.anlegen(kreditorennummer, "Kreditor " + kreditorennummer);
    }

    Debitor debitor(String vorname, String nachname) {
        return debitorService.anlegen(vorname, nachname, GEBURTSDATUM, "10115");
    }

    Forderungskonto konto(String aktenzeichen, Kreditor kreditor, Debitor debitor) {
        return kontoService.anlegen(aktenzeichen, kreditor.getId(), debitor == null ? null : debitor.getId());
    }

    void zuordnen(Forderungskonto konto, Debitor debitor) {
        kontoService.debitorZuordnen(konto.getId(), debitor.getId(), "sb-test");
    }

    Buchung soll(Long kontoId, Buchungsart art, String betrag, LocalDate valuta) {
        Buchung buchung = buchungService
                .sollbuchen(kontoId, new SollbuchungBefehl(art, new BigDecimal(betrag), valuta, naechsteRequestId()))
                .buchung();
        clock.vorstellen(SCHRITT);
        return buchung;
    }

    Buchung zahlung(Long kontoId, Debitor debitor, String betrag, LocalDate valuta) {
        Buchung buchung = buchungService
                .zahlen(kontoId, new ZahlungBefehl(debitor.getId(), new BigDecimal(betrag), valuta, naechsteRequestId()))
                .buchung();
        clock.vorstellen(SCHRITT);
        return buchung;
    }

    Buchung storno(Long kontoId, Buchung original, StornoGrund grund) {
        Buchung buchung = buchungService
                .stornieren(kontoId, original.getId(), new StornoBefehl(grund, null, "sb-mueller", naechsteRequestId()))
                .buchung();
        clock.vorstellen(SCHRITT);
        return buchung;
    }

    Buchung ausbuchung(Long kontoId) {
        Buchung buchung = buchungService
                .ausbuchen(kontoId, new AusbuchungBefehl(AusbuchungsGrund.UNEINBRINGLICH, null, "sb-mueller",
                        naechsteRequestId()))
                .buchung();
        clock.vorstellen(SCHRITT);
        return buchung;
    }

    /** Grundkonto: KR-1, AZ-1, D1 und D2 zugeordnet, HF 100 / ZINSEN 10 / GEBUEHREN 5, Saldo 115. */
    Grundkonto grundkonto() {
        Kreditor kreditor = kreditor("KR-1");
        Debitor d1 = debitor("Dora", "Eins");
        Debitor d2 = debitor("Dieter", "Zwei");
        Forderungskonto konto = konto("AZ-1", kreditor, d1);
        zuordnen(konto, d2);
        Buchung hf = soll(konto.getId(), Buchungsart.HAUPTFORDERUNG, "100.00", VALUTA_HF);
        Buchung zinsen = soll(konto.getId(), Buchungsart.ZINSEN, "10.00", VALUTA_ZINSEN);
        Buchung geb = soll(konto.getId(), Buchungsart.GEBUEHREN, "5.00", VALUTA_GEB);
        return new Grundkonto(kreditor, d1, d2, konto, hf, zinsen, geb);
    }

    /** Zustand AK-18: Grundkonto, D2 zahlt 150.00 → alles bezahlt, Guthaben D2 35.00. Liefert die Zahlung. */
    Buchung zustandAk18(Grundkonto g) {
        return zahlung(g.kontoId(), g.d2(), "150.00", VALUTA_ZAHLUNG);
    }

    record Grundkonto(Kreditor kreditor, Debitor d1, Debitor d2, Forderungskonto konto,
            Buchung hf, Buchung zinsen, Buchung geb) {

        Long kontoId() {
            return konto.getId();
        }
    }
}
