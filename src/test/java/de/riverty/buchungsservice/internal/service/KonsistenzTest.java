package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.StornoGrund;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.service.Testdaten.Grundkonto;

@ExtendWith(OutputCaptureExtension.class)
class KonsistenzTest extends ServiceTestBasis {

    @Test
    @DisplayName("S1-AK-38: Gespeicherter Saldo = berechneter Saldo nach jeder Buchung; Endsaldo -45.00, Guthaben D1 45.00")
    void saldoNachJederBuchung() {
        Grundkonto g = testdaten.grundkonto();
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");
        assertKonsistent(g.kontoId());

        testdaten.zahlung(g.kontoId(), g.d1(), "150.00", Testdaten.VALUTA_ZAHLUNG);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-35.00");
        assertKonsistent(g.kontoId());

        testdaten.storno(g.kontoId(), g.zinsen(), StornoGrund.FEHLBUCHUNG);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-45.00");
        assertThat(berechneterSaldo(g.kontoId())).isEqualByComparingTo("-45.00");
        assertThat(verrechnungService.offenePosten(g.kontoId())).isEmpty();
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("45.00");
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("0.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-39: Konsistenzpruefung OK auf jedem Zwischen- und Endzustand eines kompletten Ablaufs")
    void konsistenzUeberGesamtenAblauf() {
        Grundkonto g = testdaten.grundkonto();
        assertKonsistent(g.kontoId());

        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        assertKonsistent(g.kontoId());

        testdaten.storno(g.kontoId(), zahlung, StornoGrund.RUECKLASTSCHRIFT);
        assertKonsistent(g.kontoId());

        testdaten.zahlung(g.kontoId(), g.d2(), "20.00", Testdaten.VALUTA_ZAHLUNG);
        assertKonsistent(g.kontoId());

        Buchung ausbuchung = testdaten.ausbuchung(g.kontoId());
        assertThat(ausbuchung.getBetrag().wert()).isEqualByComparingTo("95.00");
        assertKonsistent(g.kontoId());

        testdaten.storno(g.kontoId(), ausbuchung, StornoGrund.SONSTIGES);
        assertKonsistent(g.kontoId());

        testdaten.storno(g.kontoId(), g.zinsen(), StornoGrund.FEHLBUCHUNG);
        assertKonsistent(g.kontoId());

        verrechnungService.verrechnen(g.kontoId());
        assertKonsistent(g.kontoId());
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("S1-AK-40: Manipulierter Saldo 120.00 (Soll 115.00) → ABWEICHUNG SALDO, Differenz +5.00, Error-Log")
    void saldoAbweichung(CapturedOutput output) {
        Grundkonto g = testdaten.grundkonto();
        jdbc.update("UPDATE forderungskonto SET saldo = 120.00 WHERE id = ?", g.kontoId());

        Konsistenzbericht bericht = konsistenzService.pruefen(g.kontoId());

        assertThat(bericht.status()).isEqualTo(KonsistenzStatus.ABWEICHUNG);
        assertThat(bericht.abweichungen()).singleElement().satisfies(a -> {
            assertThat(a.pruefung()).isEqualTo(Pruefung.SALDO);
            assertThat(a.buchungId()).isNull();
            assertThat(a.erwartet()).isEqualByComparingTo("115.00");
            assertThat(a.ist()).isEqualByComparingTo("120.00");
            assertThat(a.differenz()).isEqualByComparingTo("5.00");
        });
        assertThat(output.getAll()).contains("ERROR");
    }

    @Test
    @DisplayName("S1-AK-40: Sollposten ueberbezahlt (Verrechnung manipuliert) → ABWEICHUNG POSTEN_UEBERBEZAHLT, Error-Log")
    void postenUeberbezahlt(CapturedOutput output) {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zustandAk18(g);
        Verrechnung gebVerrechnung = verrechnungAuf(g.kontoId(), zahlung, g.geb());
        // GEB 5.00 → 8.00: Posten netto 8 > 5; Zahlung netto 118 <= 150 bleibt korrekt
        jdbc.update("UPDATE verrechnung SET betrag = 8.00 WHERE id = ?", gebVerrechnung.getId());

        Konsistenzbericht bericht = konsistenzService.pruefen(g.kontoId());

        assertThat(bericht.status()).isEqualTo(KonsistenzStatus.ABWEICHUNG);
        assertThat(bericht.abweichungen()).singleElement().satisfies(a -> {
            assertThat(a.pruefung()).isEqualTo(Pruefung.POSTEN_UEBERBEZAHLT);
            assertThat(a.buchungId()).isEqualTo(g.geb().getId());
            assertThat(a.erwartet()).isEqualByComparingTo("5.00");
            assertThat(a.ist()).isEqualByComparingTo("8.00");
            assertThat(a.differenz()).isEqualByComparingTo("3.00");
        });
        assertThat(output.getAll()).contains("ERROR");
    }

    @Test
    @DisplayName("S1-AK-40: Zahlung ueberverteilt (Verrechnung manipuliert) → ABWEICHUNG HABEN_UEBERVERTEILT, Error-Log")
    void zahlungUeberverteilt(CapturedOutput output) {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Verrechnung zinsVerrechnung = verrechnungAuf(g.kontoId(), zahlung, g.zinsen());
        // ZINSEN 7.00 → 9.00: Posten netto 9 <= 10 bleibt korrekt; Zahlung netto 14 > 12
        jdbc.update("UPDATE verrechnung SET betrag = 9.00 WHERE id = ?", zinsVerrechnung.getId());

        Konsistenzbericht bericht = konsistenzService.pruefen(g.kontoId());

        assertThat(bericht.status()).isEqualTo(KonsistenzStatus.ABWEICHUNG);
        assertThat(bericht.abweichungen()).singleElement().satisfies(a -> {
            assertThat(a.pruefung()).isEqualTo(Pruefung.HABEN_UEBERVERTEILT);
            assertThat(a.buchungId()).isEqualTo(zahlung.getId());
            assertThat(a.erwartet()).isEqualByComparingTo("12.00");
            assertThat(a.ist()).isEqualByComparingTo("14.00");
            assertThat(a.differenz()).isEqualByComparingTo("2.00");
        });
        assertThat(output.getAll()).contains("ERROR");
    }

    @Test
    @DisplayName("S1-AK-40: Posten und Zahlung gleichzeitig ueberverteilt → zwei Abweichungen")
    void postenUndZahlungUeberverteilt() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", LocalDate.of(2026, 3, 1));
        Verrechnung zinsVerrechnung = verrechnungAuf(g.kontoId(), zahlung, g.zinsen());
        // ZINSEN 7.00 → 12.00: Posten netto 12 > 10 (+2.00); Zahlung netto 17 > 12 (+5.00)
        jdbc.update("UPDATE verrechnung SET betrag = 12.00 WHERE id = ?", zinsVerrechnung.getId());

        Konsistenzbericht bericht = konsistenzService.pruefen(g.kontoId());

        assertThat(bericht.status()).isEqualTo(KonsistenzStatus.ABWEICHUNG);
        assertThat(bericht.abweichungen())
                .extracting(Abweichung::pruefung, Abweichung::buchungId)
                .containsExactlyInAnyOrder(
                        tuple(Pruefung.POSTEN_UEBERBEZAHLT, g.zinsen().getId()),
                        tuple(Pruefung.HABEN_UEBERVERTEILT, zahlung.getId()));
        assertThat(bericht.abweichungen())
                .filteredOn(a -> a.pruefung() == Pruefung.HABEN_UEBERVERTEILT)
                .singleElement()
                .satisfies(a -> assertThat(a.differenz()).isEqualByComparingTo("5.00"));
        assertThat(bericht.abweichungen())
                .filteredOn(a -> a.pruefung() == Pruefung.POSTEN_UEBERBEZAHLT)
                .singleElement()
                .satisfies(a -> assertThat(a.differenz()).isEqualByComparingTo("2.00"));
    }

    @Test
    @DisplayName("S1-AK-53: Mehr Aufhebung als Verrechnung (manipuliert) → ABWEICHUNG VERRECHNUNG_NEGATIV, erwartet 0.00")
    void negativerNettoVerrechnungsbetrag() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Verrechnung zinsVerrechnung = verrechnungAuf(g.kontoId(), zahlung, g.zinsen());
        Buchung storno = testdaten.storno(g.kontoId(), zahlung, StornoGrund.RUECKLASTSCHRIFT);
        Verrechnung zinsAufhebung = verrechnungenAusgeloestDurch(g.kontoId(), storno).stream()
                .filter(a -> a.getAufhebungVon().getId().equals(zinsVerrechnung.getId()))
                .findFirst()
                .orElseThrow();
        assertKonsistent(g.kontoId());
        // Aufhebung ZINSEN 7.00 → 9.00: netto (Zahlung→ZINSEN) = 7 − 9 = −2.00.
        // Zahlung netto −2 <= 12 und ZINSEN netto −2 <= 10: nur die Untergrenze 0.00 ist verletzt.
        jdbc.update("UPDATE verrechnung SET betrag = 9.00 WHERE id = ?", zinsAufhebung.getId());

        Konsistenzbericht bericht = konsistenzService.pruefen(g.kontoId());

        assertThat(bericht.status()).isEqualTo(KonsistenzStatus.ABWEICHUNG);
        assertThat(bericht.abweichungen())
                .isNotEmpty()
                .allSatisfy(a -> {
                    assertThat(a.pruefung()).isEqualTo(Pruefung.VERRECHNUNG_NEGATIV);
                    assertThat(a.buchungId()).isIn(zahlung.getId(), g.zinsen().getId());
                    assertThat(a.erwartet()).isEqualByComparingTo("0.00");
                    assertThat(a.ist()).isEqualByComparingTo("-2.00");
                    assertThat(a.differenz()).isEqualByComparingTo("-2.00");
                });
    }

    @Test
    @DisplayName("S1-AK-39: Konsistenzpruefung eines leeren Kontos → OK")
    void leeresKontoIstKonsistent() {
        Long konto = testdaten.konto("AZ-1", testdaten.kreditor("KR-1"), null).getId();

        assertKonsistent(konto);
        assertThat(berechneterSaldo(konto)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S1-AK-03: Keine Buchung im kompletten Ablauf hat einen Betrag <= 0 (auch kein Storno)")
    void keineBuchungMitNichtPositivemBetrag() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        testdaten.storno(g.kontoId(), zahlung, StornoGrund.RUECKLASTSCHRIFT);
        Buchung ausbuchung = testdaten.ausbuchung(g.kontoId());
        testdaten.storno(g.kontoId(), ausbuchung, StornoGrund.SONSTIGES);
        testdaten.soll(g.kontoId(), Buchungsart.ZINSEN, "0.005", Testdaten.VALUTA_ZINSEN);

        assertThat(buchungService.historie(g.kontoId()))
                .hasSize(8)
                .allSatisfy(b -> assertThat(b.getBetrag().wert()).isPositive());
        assertThat(verrechnungService.verrechnungen(g.kontoId()))
                .allSatisfy(v -> assertThat(v.getBetrag().wert()).isPositive());
        Integer nichtPositiv = jdbc.queryForObject(
                "SELECT COUNT(*) FROM buchung WHERE betrag <= 0", Integer.class);
        assertThat(nichtPositiv).isZero();
    }

    private Verrechnung verrechnungAuf(Long kontoId, Buchung haben, Buchung soll) {
        return verrechnungenVon(kontoId, haben).stream()
                .filter(v -> v.getSollBuchung().getId().equals(soll.getId()))
                .findFirst()
                .orElseThrow();
    }
}
