package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.domain.VerrechnungsArt;
import de.riverty.buchungsservice.internal.service.Testdaten.Grundkonto;

class ExpliziteVerrechnungTest extends ServiceTestBasis {

    private record Ak22(Long konto, Debitor d1, Debitor d2, Buchung p1, Buchung p2, Buchung geb, Buchung hf) {
    }

    private Ak22 ausgangslageAk22() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Debitor d2 = testdaten.debitor("Dieter", "Zwei");
        Forderungskonto konto = testdaten.konto("AZ-1", kr1, d1);
        testdaten.zuordnen(konto, d2);
        Buchung p1 = testdaten.zahlung(konto.getId(), d1, "30.00", LocalDate.of(2026, 3, 10));
        Buchung p2 = testdaten.zahlung(konto.getId(), d2, "20.00", LocalDate.of(2026, 3, 1));
        Buchung geb = testdaten.soll(konto.getId(), Buchungsart.GEBUEHREN, "5.00", Testdaten.VALUTA_GEB);
        Buchung hf = testdaten.soll(konto.getId(), Buchungsart.HAUPTFORDERUNG, "40.00", Testdaten.VALUTA_HF);
        return new Ak22(konto.getId(), d1, d2, p1, p2, geb, hf);
    }

    @Test
    @DisplayName("S1-AK-22: Explizite Verrechnung verteilt Guthaben, aelteste Zahlung (Valuta) zuerst, nach § 367")
    void expliziteVerrechnungVerteiltGuthaben() {
        Ak22 a = ausgangslageAk22();
        assertThat(saldo(a.konto())).isEqualByComparingTo("-5.00");
        assertThat(verrechnungService.verrechnungen(a.konto())).isEmpty();
        int buchungenVorher = anzahlBuchungen(a.konto());

        List<Verrechnung> ergebnis = verrechnungService.verrechnen(a.konto());

        assertThat(ergebnis)
                .allSatisfy(v -> {
                    assertThat(v.getId()).isNotNull();
                    assertThat(v.getArt()).isEqualTo(VerrechnungsArt.VERRECHNUNG);
                    assertThat(v.getZeitpunkt()).isEqualTo(clock.instant());
                })
                .extracting(v -> v.getHabenBuchung().getId(), v -> v.getSollBuchung().getId(), v -> v.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(a.p2().getId(), a.geb().getId(), eur("5.00")),
                        tuple(a.p2().getId(), a.hf().getId(), eur("15.00")),
                        tuple(a.p1().getId(), a.hf().getId(), eur("25.00")));
        assertThat(verrechnungService.verrechnungen(a.konto())).hasSize(3);
        assertThat(verrechnungService.offenePosten(a.konto())).isEmpty();
        assertThat(guthaben(a.konto(), a.d1())).isEqualByComparingTo("5.00");
        assertThat(guthaben(a.konto(), a.d2())).isEqualByComparingTo("0.00");
        assertThat(saldo(a.konto())).isEqualByComparingTo("-5.00");
        assertThat(anzahlBuchungen(a.konto())).isEqualTo(buchungenVorher);
        assertKonsistent(a.konto());
    }

    @Test
    @DisplayName("S1-AK-23: Zweite explizite Verrechnung ist No-op (leeres Ergebnis, kein Fehler, nichts gebucht)")
    void zweiteVerrechnungIstNoop() {
        Ak22 a = ausgangslageAk22();
        verrechnungService.verrechnen(a.konto());
        int buchungenVorher = anzahl("buchung");
        int verrechnungenVorher = anzahl("verrechnung");

        List<Verrechnung> zweiterLauf = verrechnungService.verrechnen(a.konto());

        assertThat(zweiterLauf).isEmpty();
        assertThat(anzahl("buchung")).isEqualTo(buchungenVorher);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungenVorher);
        assertThat(saldo(a.konto())).isEqualByComparingTo("-5.00");
        assertKonsistent(a.konto());
    }

    @Test
    @DisplayName("S1-AK-23: Explizite Verrechnung ohne Guthaben bzw. ohne offene Posten ist No-op")
    void verrechnungOhneGuthabenOderOhnePosten() {
        Grundkonto g = testdaten.grundkonto();

        assertThat(verrechnungService.verrechnen(g.kontoId())).isEmpty();
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");

        Debitor d3 = testdaten.debitor("Doris", "Drei");
        Long ohnePosten = testdaten.konto("AZ-2", g.kreditor(), d3).getId();
        testdaten.zahlung(ohnePosten, d3, "50.00", Testdaten.VALUTA_ZAHLUNG);

        assertThat(verrechnungService.verrechnen(ohnePosten)).isEmpty();
        assertThat(guthaben(ohnePosten, d3)).isEqualByComparingTo("50.00");

        Long leer = testdaten.konto("AZ-3", g.kreditor(), null).getId();
        assertThat(verrechnungService.verrechnen(leer)).isEmpty();

        assertThat(anzahl("verrechnung")).isZero();
    }

    @Test
    @DisplayName("S1-AK-55: Explizite Verrechnung auf unbekanntes Konto → NichtGefunden")
    void verrechnungUnbekanntesKonto() {
        assertThatThrownBy(() -> verrechnungService.verrechnen(UNBEKANNT))
                .isInstanceOf(NichtGefundenException.class);
        assertThat(anzahl("verrechnung")).isZero();
    }

    @Test
    @DisplayName("S1-AK-55: Posten gleicher Art, Valuta und Buchungszeit → kleinere Id wird zuerst verrechnet")
    void gleicheArtValutaUndBuchungszeitKleinereIdZuerst() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Long konto = testdaten.konto("AZ-1", kr1, d1).getId();
        testdaten.zahlung(konto, d1, "10.00", Testdaten.VALUTA_ZAHLUNG);
        // Clock bewusst NICHT vorstellen: beide Posten erhalten dieselbe Buchungszeit.
        Buchung erster = buchungService.sollbuchen(konto,
                new SollbuchungBefehl(Buchungsart.ZINSEN, eur("10.00"), Testdaten.VALUTA_ZINSEN, "soll-gleich-1")).buchung();
        Buchung zweiter = buchungService.sollbuchen(konto,
                new SollbuchungBefehl(Buchungsart.ZINSEN, eur("10.00"), Testdaten.VALUTA_ZINSEN, "soll-gleich-2")).buchung();
        assertThat(erster.getBuchungszeit()).isEqualTo(zweiter.getBuchungszeit());
        assertThat(erster.getId()).isLessThan(zweiter.getId());
        assertThat(offenePostenIds(konto)).containsExactly(erster.getId(), zweiter.getId());

        List<Verrechnung> ergebnis = verrechnungService.verrechnen(konto);

        assertThat(ergebnis).singleElement().satisfies(v -> {
            assertThat(v.getSollBuchung().getId()).isEqualTo(erster.getId());
            assertThat(v.getBetrag().wert()).isEqualByComparingTo("10.00");
        });
        assertThat(offen(konto, erster)).isEqualByComparingTo("0.00");
        assertThat(offen(konto, zweiter)).isEqualByComparingTo("10.00");
        assertThat(offenePostenIds(konto)).containsExactly(zweiter.getId());
        assertKonsistent(konto);
    }

    @Test
    @DisplayName("S1-AK-55: Gleiche Art, Valuta und Buchungszeit → automatische Verrechnung einer Zahlung trifft kleinere Id zuerst")
    void automatischeVerrechnungBeiGleicherBuchungszeitKleinereIdZuerst() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Long konto = testdaten.konto("AZ-1", kr1, d1).getId();
        Buchung erster = buchungService.sollbuchen(konto,
                new SollbuchungBefehl(Buchungsart.GEBUEHREN, eur("5.00"), Testdaten.VALUTA_GEB, "soll-gleich-a")).buchung();
        Buchung zweiter = buchungService.sollbuchen(konto,
                new SollbuchungBefehl(Buchungsart.GEBUEHREN, eur("5.00"), Testdaten.VALUTA_GEB, "soll-gleich-b")).buchung();
        assertThat(erster.getBuchungszeit()).isEqualTo(zweiter.getBuchungszeit());
        clock.vorstellen(Testdaten.SCHRITT);

        Buchung zahlung = testdaten.zahlung(konto, d1, "3.00", Testdaten.VALUTA_ZAHLUNG);

        assertThat(verrechnungenVon(konto, zahlung)).singleElement().satisfies(v -> {
            assertThat(v.getSollBuchung().getId()).isEqualTo(erster.getId());
            assertThat(v.getBetrag().wert()).isEqualByComparingTo("3.00");
        });
        assertThat(offen(konto, erster)).isEqualByComparingTo("2.00");
        assertThat(offen(konto, zweiter)).isEqualByComparingTo("5.00");
        assertKonsistent(konto);
    }

    @Test
    @DisplayName("S1-AK-24: Guthaben ist kontobezogen – Guthaben auf AZ-2 wird nicht auf AZ-1 verrechnet")
    void guthabenIstKontobezogen() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Long az1 = testdaten.konto("AZ-1", kr1, d1).getId();
        Long az2 = testdaten.konto("AZ-2", kr1, d1).getId();
        testdaten.zahlung(az2, d1, "30.00", Testdaten.VALUTA_ZAHLUNG);
        Buchung zinsen = testdaten.soll(az1, Buchungsart.ZINSEN, "10.00", Testdaten.VALUTA_ZINSEN);

        List<Verrechnung> ergebnis = verrechnungService.verrechnen(az1);

        assertThat(ergebnis).isEmpty();
        assertThat(offen(az1, zinsen)).isEqualByComparingTo("10.00");
        assertThat(guthaben(az1, d1)).isEqualByComparingTo("0.00");
        assertThat(guthaben(az2, d1)).isEqualByComparingTo("30.00");
        assertThat(saldo(az1)).isEqualByComparingTo("10.00");
        assertThat(saldo(az2)).isEqualByComparingTo("-30.00");
        assertKonsistent(az1);
        assertKonsistent(az2);
    }
}
