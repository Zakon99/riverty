package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.FachlicheRegelVerletztException;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.domain.VerrechnungsArt;
import de.riverty.buchungsservice.internal.service.Testdaten.Grundkonto;

class ZahlungUndVerrechnungTest extends ServiceTestBasis {

    @Test
    @DisplayName("S1-AK-16: D1 zahlt 12.00 → GEBUEHREN 5.00, ZINSEN 7.00 verrechnet, Saldo 103.00")
    void zahlungVerrechnetNachParagraph367() {
        Grundkonto g = testdaten.grundkonto();
        Instant jetzt = clock.instant();

        BuchungsErgebnis ergebnis = buchungService.zahlen(g.kontoId(),
                new ZahlungBefehl(g.d1().getId(), eur("12.00"), LocalDate.of(2026, 3, 1), "zahlung-16"));

        Buchung zahlung = ergebnis.buchung();
        assertThat(ergebnis.neuAngelegt()).isTrue();
        assertThat(zahlung.getArt()).isEqualTo(Buchungsart.ZAHLUNG);
        assertThat(zahlung.getBetrag().wert()).isEqualByComparingTo("12.00");
        assertThat(zahlung.getValuta()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(zahlung.getBuchungszeit()).isEqualTo(jetzt);
        assertThat(zahlung.getDebitor().getId()).isEqualTo(g.d1().getId());
        assertThat(zahlung.getKreditor()).isNull();

        assertThat(verrechnungService.verrechnungen(g.kontoId()))
                .allSatisfy(v -> {
                    assertThat(v.getArt()).isEqualTo(VerrechnungsArt.VERRECHNUNG);
                    assertThat(v.getHabenBuchung().getId()).isEqualTo(zahlung.getId());
                    assertThat(v.getZeitpunkt()).isEqualTo(jetzt);
                    assertThat(v.getAufhebungVon()).isNull();
                })
                .extracting(v -> v.getSollBuchung().getId(), v -> v.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(g.geb().getId(), eur("5.00")),
                        tuple(g.zinsen().getId(), eur("7.00")));

        assertThat(offen(g.kontoId(), g.geb())).isEqualByComparingTo("0.00");
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("3.00");
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("100.00");
        assertThat(offenePostenIds(g.kontoId())).containsExactly(g.zinsen().getId(), g.hf().getId());
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("103.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("0.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-17: Innerhalb einer Art aelteste Valuta zuerst, bei gleicher Valuta fruehere Buchungszeit")
    void reihenfolgeInnerhalbDerArt() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Long konto = testdaten.konto("AZ-1", kr1, d1).getId();
        Buchung zA = testdaten.soll(konto, Buchungsart.ZINSEN, "10.00", LocalDate.of(2026, 3, 1));
        Buchung zB = testdaten.soll(konto, Buchungsart.ZINSEN, "10.00", LocalDate.of(2026, 2, 1));
        Buchung zC = testdaten.soll(konto, Buchungsart.ZINSEN, "10.00", LocalDate.of(2026, 2, 1));
        Buchung hf = testdaten.soll(konto, Buchungsart.HAUPTFORDERUNG, "50.00", LocalDate.of(2026, 1, 1));
        assertThat(saldo(konto)).isEqualByComparingTo("80.00");

        Buchung zahlung = testdaten.zahlung(konto, d1, "15.00", LocalDate.of(2026, 3, 15));

        assertThat(verrechnungenVon(konto, zahlung))
                .extracting(v -> v.getSollBuchung().getId(), v -> v.getBetrag().wert())
                .containsExactlyInAnyOrder(tuple(zB.getId(), eur("10.00")), tuple(zC.getId(), eur("5.00")));
        assertThat(offen(konto, zB)).isEqualByComparingTo("0.00");
        assertThat(offen(konto, zC)).isEqualByComparingTo("5.00");
        assertThat(offen(konto, zA)).isEqualByComparingTo("10.00");
        assertThat(offen(konto, hf)).isEqualByComparingTo("50.00");
        assertThat(offenePostenIds(konto)).containsExactly(zC.getId(), zA.getId(), hf.getId());
        assertThat(saldo(konto)).isEqualByComparingTo("65.00");
        assertKonsistent(konto);
    }

    @Test
    @DisplayName("S1-AK-18: D2 zahlt 150.00 → alles verrechnet, Guthaben D2 35.00, Saldo -35.00")
    void ueberzahlungErzeugtGuthabenBeimZahler() {
        Grundkonto g = testdaten.grundkonto();

        Buchung zahlung = testdaten.zustandAk18(g);

        assertThat(zahlung.getDebitor().getId()).isEqualTo(g.d2().getId());
        assertThat(verrechnungenVon(g.kontoId(), zahlung))
                .extracting(v -> v.getSollBuchung().getId(), v -> v.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(g.geb().getId(), eur("5.00")),
                        tuple(g.zinsen().getId(), eur("10.00")),
                        tuple(g.hf().getId(), eur("100.00")));
        assertThat(verrechnungService.offenePosten(g.kontoId())).isEmpty();
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("35.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-35.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-19: Zahlung ohne offene Posten → keine Verrechnung, komplett Guthaben")
    void zahlungOhneOffenePosten() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Long konto = testdaten.konto("AZ-1", kr1, d1).getId();

        testdaten.zahlung(konto, d1, "50.00", Testdaten.VALUTA_ZAHLUNG);

        assertThat(verrechnungService.verrechnungen(konto)).isEmpty();
        assertThat(guthaben(konto, d1)).isEqualByComparingTo("50.00");
        assertThat(saldo(konto)).isEqualByComparingTo("-50.00");
        assertKonsistent(konto);
    }

    @Test
    @DisplayName("S1-AK-20: Neue Sollbuchung nutzt Altguthaben nicht automatisch; neue Zahlung verteilt nur sich selbst")
    void automatischeVerrechnungNurNeueZahlung() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zustandAk18(g);

        Buchung zinsen2 = testdaten.soll(g.kontoId(), Buchungsart.ZINSEN, "20.00", LocalDate.of(2026, 4, 1));

        assertThat(verrechnungService.verrechnungen(g.kontoId())).hasSize(3);
        assertThat(offen(g.kontoId(), zinsen2)).isEqualByComparingTo("20.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("35.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-15.00");
        assertKonsistent(g.kontoId());

        Buchung zahlungD1 = testdaten.zahlung(g.kontoId(), g.d1(), "8.00", LocalDate.of(2026, 4, 10));

        assertThat(verrechnungService.verrechnungen(g.kontoId())).hasSize(4);
        assertThat(verrechnungenVon(g.kontoId(), zahlungD1))
                .extracting(v -> v.getSollBuchung().getId(), v -> v.getBetrag().wert())
                .containsExactly(tuple(zinsen2.getId(), eur("8.00")));
        assertThat(offen(g.kontoId(), zinsen2)).isEqualByComparingTo("12.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("35.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-23.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-21: Zahlung von nicht zugeordnetem Debitor oder ohne Debitor → FachlicheRegelVerletzt")
    void zahlungVonNichtZugeordnetemDebitor() {
        Grundkonto g = testdaten.grundkonto();
        Debitor d3 = testdaten.debitor("Doris", "Drei");
        ZahlungBefehl vonD3 = new ZahlungBefehl(d3.getId(), eur("10.00"), Testdaten.VALUTA_ZAHLUNG, "zahlung-d3");
        ZahlungBefehl ohneDebitor = new ZahlungBefehl(null, eur("10.00"), Testdaten.VALUTA_ZAHLUNG, "zahlung-ohne");

        assertThatThrownBy(() -> buchungService.zahlen(g.kontoId(), vonD3))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThatThrownBy(() -> buchungService.zahlen(g.kontoId(), ohneDebitor))
                .isInstanceOf(FachlicheRegelVerletztException.class);

        assertNichtsGebucht(g);
    }

    @Test
    @DisplayName("S1-AK-21: Zahlung mit unbekanntem Debitor oder auf unbekanntes Konto → NichtGefunden")
    void zahlungMitUnbekanntenIds() {
        Grundkonto g = testdaten.grundkonto();
        ZahlungBefehl unbekannterDebitor =
                new ZahlungBefehl(UNBEKANNT, eur("10.00"), Testdaten.VALUTA_ZAHLUNG, "zahlung-unbekannt");
        ZahlungBefehl aufUnbekanntesKonto =
                new ZahlungBefehl(g.d1().getId(), eur("10.00"), Testdaten.VALUTA_ZAHLUNG, "zahlung-konto");

        assertThatThrownBy(() -> buchungService.zahlen(g.kontoId(), unbekannterDebitor))
                .isInstanceOf(NichtGefundenException.class);
        assertThatThrownBy(() -> buchungService.zahlen(UNBEKANNT, aufUnbekanntesKonto))
                .isInstanceOf(NichtGefundenException.class);

        assertNichtsGebucht(g);
    }

    private void assertNichtsGebucht(Grundkonto g) {
        assertThat(anzahl("buchung")).isEqualTo(3);
        assertThat(anzahl("verrechnung")).isZero();
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");
    }

    @Test
    @DisplayName("S1-AK-03: Keine Verrechnung hat einen Betrag <= 0")
    void keineVerrechnungMitNullbetrag() {
        Grundkonto g = testdaten.grundkonto();
        testdaten.zahlung(g.kontoId(), g.d1(), "5.00", Testdaten.VALUTA_ZAHLUNG);

        assertThat(verrechnungService.verrechnungen(g.kontoId()))
                .extracting(Verrechnung::getSollBuchung)
                .extracting(Buchung::getId)
                .containsExactly(g.geb().getId());
        assertThat(verrechnungService.verrechnungen(g.kontoId()))
                .allSatisfy(v -> assertThat(v.getBetrag().wert()).isPositive());
    }

    @Test
    @DisplayName("S1-AK-21: Zahlung eines Debitors, der nur einem anderen Konto zugeordnet ist → FachlicheRegelVerletzt")
    void debitorNurAnderemKontoZugeordnet() {
        Grundkonto g = testdaten.grundkonto();
        Debitor d3 = testdaten.debitor("Doris", "Drei");
        Forderungskonto az2 = testdaten.konto("AZ-2", g.kreditor(), d3);
        ZahlungBefehl befehl = new ZahlungBefehl(d3.getId(), eur("10.00"), Testdaten.VALUTA_ZAHLUNG, "zahlung-az2");

        assertThatThrownBy(() -> buchungService.zahlen(g.kontoId(), befehl))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThat(anzahlBuchungen(az2.getId())).isZero();
        assertNichtsGebucht(g);
    }

    @Test
    @DisplayName("S1-AK-41: Zwei parallele Zahlungen (D1 10.00, D2 20.00) → beide gebucht, Saldo 85.00, konsistent")
    void paralleleZahlungenAufDasselbeKonto() throws Exception {
        Grundkonto g = testdaten.grundkonto();

        List<Ausgang<BuchungsErgebnis>> ausgaenge = parallel(
                () -> buchungService.zahlen(g.kontoId(),
                        new ZahlungBefehl(g.d1().getId(), eur("10.00"), Testdaten.VALUTA_ZAHLUNG, "parallel-d1")),
                () -> buchungService.zahlen(g.kontoId(),
                        new ZahlungBefehl(g.d2().getId(), eur("20.00"), Testdaten.VALUTA_ZAHLUNG, "parallel-d2")));

        assertBeideNeuGebucht(ausgaenge);
        assertThat(buchungService.historie(g.kontoId()))
                .filteredOn(b -> b.getArt() == Buchungsart.ZAHLUNG)
                .extracting(b -> b.getDebitor().getId(), b -> b.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(g.d1().getId(), eur("10.00")),
                        tuple(g.d2().getId(), eur("20.00")));
        // Unabhaengig von der Reihenfolge: 30.00 verteilt auf GEBUEHREN 5, ZINSEN 10, HF 15.
        assertThat(offen(g.kontoId(), g.geb())).isEqualByComparingTo("0.00");
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("0.00");
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("85.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("0.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("85.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-41: Parallele Zahlungen ueber mehrere Runden → nie ein Fehler, jeder Saldo und jede Konsistenz korrekt")
    void paralleleZahlungenWiederholt() throws Exception {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Debitor d2 = testdaten.debitor("Dieter", "Zwei");

        for (int runde = 1; runde <= 5; runde++) {
            Forderungskonto konto = testdaten.konto("AZ-41-" + runde, kr1, d1);
            testdaten.zuordnen(konto, d2);
            testdaten.soll(konto.getId(), Buchungsart.HAUPTFORDERUNG, "100.00", Testdaten.VALUTA_HF);
            testdaten.soll(konto.getId(), Buchungsart.ZINSEN, "10.00", Testdaten.VALUTA_ZINSEN);
            testdaten.soll(konto.getId(), Buchungsart.GEBUEHREN, "5.00", Testdaten.VALUTA_GEB);
            String praefix = "parallel-" + runde + "-";

            List<Ausgang<BuchungsErgebnis>> ausgaenge = parallel(
                    () -> buchungService.zahlen(konto.getId(),
                            new ZahlungBefehl(d1.getId(), eur("10.00"), Testdaten.VALUTA_ZAHLUNG, praefix + "d1")),
                    () -> buchungService.zahlen(konto.getId(),
                            new ZahlungBefehl(d2.getId(), eur("20.00"), Testdaten.VALUTA_ZAHLUNG, praefix + "d2")));

            assertBeideNeuGebucht(ausgaenge);
            assertThat(saldo(konto.getId())).isEqualByComparingTo("85.00");
            assertKonsistent(konto.getId());
        }
    }

    private static void assertBeideNeuGebucht(List<Ausgang<BuchungsErgebnis>> ausgaenge) {
        assertThat(ausgaenge).hasSize(2).allSatisfy(a -> {
            assertThat(a.fehler()).as("kein Fehler bei parallelem Zahlungseingang").isNull();
            assertThat(a.ergebnis().neuAngelegt()).isTrue();
        });
        assertThat(ausgaenge.get(0).ergebnis().buchung().getId())
                .isNotEqualTo(ausgaenge.get(1).ergebnis().buchung().getId());
    }
}
