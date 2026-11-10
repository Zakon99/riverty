package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.AusbuchungsGrund;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.FachlicheRegelVerletztException;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.StornoGrund;
import de.riverty.buchungsservice.internal.domain.VerrechnungsArt;
import de.riverty.buchungsservice.internal.service.Testdaten.Grundkonto;

class AusbuchungTest extends ServiceTestBasis {

    private static final LocalDate HEUTE = LocalDate.of(2026, 11, 10);

    private BuchungsErgebnis ausbuchen(Long kontoId, String clientRequestId) {
        return buchungService.ausbuchen(kontoId,
                new AusbuchungBefehl(AusbuchungsGrund.UNEINBRINGLICH, null, "sb-mueller", clientRequestId));
    }

    @Test
    @DisplayName("S1-AK-33: Ausbuchung des offenen Rests → AUSBUCHUNG 103.00, Valuta aus Clock, Saldo 0.00")
    void ausbuchungOffenerRest() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Momentaufnahme vorher = momentaufnahme();
        Instant jetzt = clock.instant();

        BuchungsErgebnis ergebnis = ausbuchen(g.kontoId(), "ausbuchung-33");

        Buchung ausbuchung = ergebnis.buchung();
        assertThat(ergebnis.neuAngelegt()).isTrue();
        assertThat(ausbuchung.getArt()).isEqualTo(Buchungsart.AUSBUCHUNG);
        assertThat(ausbuchung.getBetrag().wert()).isEqualByComparingTo("103.00");
        assertThat(ausbuchung.getValuta()).isEqualTo(HEUTE);
        assertThat(ausbuchung.getBuchungszeit()).isEqualTo(jetzt);
        assertThat(ausbuchung.getGrund()).isEqualTo("UNEINBRINGLICH");
        assertThat(ausbuchung.getAusloeser()).isEqualTo("sb-mueller");
        assertThat(ausbuchung.getDebitor()).isNull();
        assertThat(verrechnungenVon(g.kontoId(), ausbuchung))
                .allSatisfy(v -> {
                    assertThat(v.getArt()).isEqualTo(VerrechnungsArt.VERRECHNUNG);
                    assertThat(v.getZeitpunkt()).isEqualTo(jetzt);
                })
                .extracting(v -> v.getSollBuchung().getId(), v -> v.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(g.zinsen().getId(), eur("3.00")),
                        tuple(g.hf().getId(), eur("100.00")));
        assertThat(verrechnungService.offenePosten(g.kontoId())).isEmpty();
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("0.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("0.00");
        assertNurNeueDatensaetze(vorher);
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-34: Ausbuchung umfasst auch Posten mit Valuta in der Zukunft → 110.50")
    void ausbuchungInklusiveZukunftsposten() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Buchung zukunft = testdaten.soll(g.kontoId(), Buchungsart.GEBUEHREN, "7.50", LocalDate.of(2027, 1, 15));

        Buchung ausbuchung = ausbuchen(g.kontoId(), "ausbuchung-34").buchung();

        assertThat(ausbuchung.getBetrag().wert()).isEqualByComparingTo("110.50");
        assertThat(verrechnungenVon(g.kontoId(), ausbuchung))
                .extracting(v -> v.getSollBuchung().getId(), v -> v.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(zukunft.getId(), eur("7.50")),
                        tuple(g.zinsen().getId(), eur("3.00")),
                        tuple(g.hf().getId(), eur("100.00")));
        assertThat(verrechnungService.offenePosten(g.kontoId())).isEmpty();
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("0.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-35a: Ausbuchung auf Konto ohne Sollbuchungen → FachlicheRegelVerletzt, keine Buchung")
    void ausbuchungOhneSollbuchungen() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Long konto = testdaten.konto("AZ-1", kr1, null).getId();

        assertThatThrownBy(() -> ausbuchen(konto, "ausbuchung-leer"))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThat(anzahl("buchung")).isZero();
        assertThat(saldo(konto)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S1-AK-35b: Ausbuchung bei vollstaendig bezahltem Konto mit Guthaben → FachlicheRegelVerletzt")
    void ausbuchungBeiBezahltemKonto() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zustandAk18(g);
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");

        assertThatThrownBy(() -> ausbuchen(g.kontoId(), "ausbuchung-bezahlt"))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-35.00");
    }

    @Test
    @DisplayName("S1-AK-35c: Ausbuchung bei offenen Posten und gleichzeitig Guthaben → FachlicheRegelVerletzt")
    void ausbuchungBeiOffenenPostenUndGuthaben() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zustandAk18(g);
        Buchung zinsen2 = testdaten.soll(g.kontoId(), Buchungsart.ZINSEN, "20.00", LocalDate.of(2026, 4, 1));
        testdaten.zahlung(g.kontoId(), g.d1(), "8.00", LocalDate.of(2026, 4, 10));
        assertThat(offen(g.kontoId(), zinsen2)).isEqualByComparingTo("12.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("35.00");
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");

        assertThatThrownBy(() -> ausbuchen(g.kontoId(), "ausbuchung-mit-guthaben"))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-23.00");
    }

    @Test
    @DisplayName("S1-AK-36: Storno der Ausbuchung → STORNO 103.00 mit Valuta der Ausbuchung, Aufhebungen, Posten offen")
    void stornoDerAusbuchung() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Buchung ausbuchung = testdaten.ausbuchung(g.kontoId());
        Momentaufnahme vorher = momentaufnahme();
        clock.setzen(Instant.parse("2026-11-11T10:00:00Z"));

        Buchung storno = buchungService.stornieren(g.kontoId(), ausbuchung.getId(),
                new StornoBefehl(StornoGrund.SONSTIGES, null, "sb-mueller", "storno-36")).buchung();

        assertThat(storno.getArt()).isEqualTo(Buchungsart.STORNO);
        assertThat(storno.getBetrag().wert()).isEqualByComparingTo("103.00");
        assertThat(storno.getValuta()).isEqualTo(HEUTE);
        assertThat(storno.getStornoVon().getId()).isEqualTo(ausbuchung.getId());
        assertThat(storno.getGrund()).isEqualTo("SONSTIGES");
        assertThat(verrechnungenAusgeloestDurch(g.kontoId(), storno))
                .allSatisfy(a -> {
                    assertThat(a.getArt()).isEqualTo(VerrechnungsArt.AUFHEBUNG);
                    assertThat(a.getHabenBuchung().getId()).isEqualTo(ausbuchung.getId());
                })
                .extracting(a -> a.getSollBuchung().getId(), a -> a.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(g.zinsen().getId(), eur("3.00")),
                        tuple(g.hf().getId(), eur("100.00")));
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("3.00");
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("100.00");
        assertThat(offenePostenIds(g.kontoId())).containsExactly(g.zinsen().getId(), g.hf().getId());
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("103.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("0.00");
        assertNurNeueDatensaetze(vorher);
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-37: Valuta der Ausbuchung ist das UTC-Datum der Clock (23:30Z → 2026-11-10)")
    void valutaInUtc() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Long konto = testdaten.konto("AZ-1", kr1, d1).getId();
        testdaten.soll(konto, Buchungsart.HAUPTFORDERUNG, "100.00", Testdaten.VALUTA_HF);
        clock.setzen(Instant.parse("2026-11-10T23:30:00Z"));

        Buchung ausbuchung = ausbuchen(konto, "ausbuchung-37").buchung();

        assertThat(ausbuchung.getValuta()).isEqualTo(HEUTE);
        assertThat(ausbuchung.getBuchungszeit()).isEqualTo(Instant.parse("2026-11-10T23:30:00Z"));
    }

    @Test
    @DisplayName("S1-AK-57: Ausbuchungs-Valuta bei Clock-Zone Europe/Berlin und 23:30Z ist das UTC-Datum 2026-11-10")
    void valutaInUtcAuchBeiAndererClockZone() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Long konto = testdaten.konto("AZ-1", kr1, d1).getId();
        testdaten.soll(konto, Buchungsart.HAUPTFORDERUNG, "100.00", Testdaten.VALUTA_HF);
        clock.setzen(Instant.parse("2026-11-10T23:30:00Z"));
        clock.zoneSetzen(ZoneId.of("Europe/Berlin"));
        try {
            // In Berlin ist es bereits 2026-11-11 00:30 – massgeblich ist aber das UTC-Datum.
            Buchung ausbuchung = ausbuchen(konto, "ausbuchung-57").buchung();

            assertThat(ausbuchung.getValuta()).isEqualTo(HEUTE);
            assertThat(ausbuchung.getBuchungszeit()).isEqualTo(Instant.parse("2026-11-10T23:30:00Z"));
        } finally {
            clock.zoneSetzen(ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("S1-AK-54: Ausbuchung mit grund null bzw. ausloeser null/blank → FachlicheRegelVerletzt, nichts persistiert")
    void ausbuchungOhneGrundOderAusloeser() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Momentaufnahme vorher = momentaufnahme();
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");

        assertThatThrownBy(() -> buchungService.ausbuchen(g.kontoId(),
                new AusbuchungBefehl(null, null, "sb-mueller", "ausbuchung-54-grund")))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThatThrownBy(() -> buchungService.ausbuchen(g.kontoId(),
                new AusbuchungBefehl(AusbuchungsGrund.UNEINBRINGLICH, null, null, "ausbuchung-54-null")))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThatThrownBy(() -> buchungService.ausbuchen(g.kontoId(),
                new AusbuchungBefehl(AusbuchungsGrund.UNEINBRINGLICH, null, "  ", "ausbuchung-54-blank")))
                .isInstanceOf(FachlicheRegelVerletztException.class);

        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(momentaufnahme()).isEqualTo(vorher);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("103.00");
        assertKonsistent(g.kontoId());

        Buchung ausbuchung = ausbuchen(g.kontoId(), "ausbuchung-54-grund").buchung();
        assertThat(ausbuchung.getBetrag().wert())
                .as("abgelehnte clientRequestId ist nicht verbraucht")
                .isEqualByComparingTo("103.00");
    }

    @Test
    @DisplayName("S1-AK-33: Ausbuchung erzeugt nie Guthaben und hat stets Betrag > 0")
    void ausbuchungErzeugtKeinGuthaben() {
        Grundkonto g = testdaten.grundkonto();

        Buchung ausbuchung = ausbuchen(g.kontoId(), "ausbuchung-voll").buchung();

        assertThat(ausbuchung.getBetrag().wert()).isEqualByComparingTo("115.00");
        assertThat(verrechnungenVon(g.kontoId(), ausbuchung).stream()
                .map(v -> v.getBetrag().wert())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("115.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("0.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("0.00");
        assertKonsistent(g.kontoId());
    }
}
