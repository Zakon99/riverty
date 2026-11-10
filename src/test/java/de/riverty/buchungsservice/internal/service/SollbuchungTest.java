package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.FachlicheRegelVerletztException;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;
import de.riverty.buchungsservice.internal.domain.UngueltigerBetragException;

class SollbuchungTest extends ServiceTestBasis {

    private Kreditor kr1;
    private Debitor d1;
    private Forderungskonto az1;

    @BeforeEach
    void leeresKonto() {
        kr1 = testdaten.kreditor("KR-1");
        d1 = testdaten.debitor("Dora", "Eins");
        az1 = testdaten.konto("AZ-1", kr1, d1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Buchungsart.class, names = {"HAUPTFORDERUNG", "ZINSEN", "GEBUEHREN"})
    @DisplayName("S1-AK-13: Sollbuchung auf leeres Konto → Buchung mit Clock-Zeit und Kreditor, Saldo und offener Posten")
    void sollbuchungAufLeeresKonto(Buchungsart art) {
        Instant jetzt = clock.instant();
        LocalDate valuta = LocalDate.of(2026, 1, 10);

        BuchungsErgebnis ergebnis = buchungService.sollbuchen(az1.getId(),
                new SollbuchungBefehl(art, eur("100.00"), valuta, "soll-1"));

        Buchung buchung = ergebnis.buchung();
        assertThat(ergebnis.neuAngelegt()).isTrue();
        assertThat(buchung.getId()).isNotNull();
        assertThat(buchung.getKonto().getId()).isEqualTo(az1.getId());
        assertThat(buchung.getArt()).isEqualTo(art);
        assertThat(buchung.getBetrag().wert()).isEqualByComparingTo("100.00");
        assertThat(buchung.getValuta()).isEqualTo(valuta);
        assertThat(buchung.getBuchungszeit()).isEqualTo(jetzt);
        assertThat(buchung.getKreditor().getId()).isEqualTo(kr1.getId());
        assertThat(buchung.getDebitor()).isNull();
        assertThat(buchung.getStornoVon()).isNull();
        assertThat(saldo(az1.getId())).isEqualByComparingTo("100.00");
        assertThat(verrechnungService.offenePosten(az1.getId())).singleElement().satisfies(p -> {
            assertThat(p.buchungId()).isEqualTo(buchung.getId());
            assertThat(p.art()).isEqualTo(art);
            assertThat(p.betrag()).isEqualByComparingTo("100.00");
            assertThat(p.offen()).isEqualByComparingTo("100.00");
            assertThat(p.valuta()).isEqualTo(valuta);
            assertThat(p.buchungszeit()).isEqualTo(jetzt);
        });
        assertThat(verrechnungService.verrechnungen(az1.getId())).isEmpty();
        assertKonsistent(az1.getId());
    }

    @Test
    @DisplayName("S1-AK-13: Mehrere Sollbuchungen summieren sich im Saldo")
    void mehrereSollbuchungen() {
        testdaten.soll(az1.getId(), Buchungsart.HAUPTFORDERUNG, "100.00", Testdaten.VALUTA_HF);
        testdaten.soll(az1.getId(), Buchungsart.ZINSEN, "10.00", Testdaten.VALUTA_ZINSEN);
        testdaten.soll(az1.getId(), Buchungsart.GEBUEHREN, "5.00", Testdaten.VALUTA_GEB);

        assertThat(saldo(az1.getId())).isEqualByComparingTo("115.00");
        assertThat(verrechnungService.offenePosten(az1.getId())).hasSize(3);
        assertKonsistent(az1.getId());
    }

    @Test
    @DisplayName("S1-AK-14: Sollbuchung mit Valuta in der Zukunft wird angelegt")
    void valutaInDerZukunft() {
        Buchung buchung = testdaten.soll(az1.getId(), Buchungsart.GEBUEHREN, "7.50", LocalDate.of(2027, 1, 15));

        assertThat(buchung.getValuta()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(saldo(az1.getId())).isEqualByComparingTo("7.50");
        assertThat(offen(az1.getId(), buchung)).isEqualByComparingTo("7.50");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Buchungsart.class, names = {"ZAHLUNG", "AUSBUCHUNG", "STORNO"})
    @DisplayName("S1-AK-15: Sollbuchung mit Haben-/Storno-Art → FachlicheRegelVerletzt, nichts gebucht")
    void sollbuchungMitFalscherArt(Buchungsart art) {
        SollbuchungBefehl befehl = new SollbuchungBefehl(art, eur("10.00"), Testdaten.VALUTA_HF, "soll-falsch");

        assertThatThrownBy(() -> buchungService.sollbuchen(az1.getId(), befehl))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThat(anzahl("buchung")).isZero();
        assertThat(saldo(az1.getId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S1-AK-15: Sollbuchung auf unbekanntes Konto → NichtGefunden")
    void sollbuchungAufUnbekanntesKonto() {
        SollbuchungBefehl befehl =
                new SollbuchungBefehl(Buchungsart.ZINSEN, eur("10.00"), Testdaten.VALUTA_HF, "soll-unbekannt");

        assertThatThrownBy(() -> buchungService.sollbuchen(UNBEKANNT, befehl))
                .isInstanceOf(NichtGefundenException.class);
        assertThat(anzahl("buchung")).isZero();
    }

    @Test
    @DisplayName("S1-AK-03: Sollbuchung ZINSEN 99.995 → gespeichert 100.00, Saldo +100.00")
    void betragWirdBeimBuchenGerundet() {
        Buchung buchung = testdaten.soll(az1.getId(), Buchungsart.ZINSEN, "99.995", Testdaten.VALUTA_ZINSEN);

        assertThat(buchung.getBetrag().wert()).isEqualByComparingTo("100.00");
        assertThat(buchung.getBetrag().wert().scale()).isEqualTo(2);
        assertThat(buchungService.historie(az1.getId())).singleElement()
                .satisfies(b -> assertThat(b.getBetrag().wert()).isEqualByComparingTo("100.00"));
        assertThat(saldo(az1.getId())).isEqualByComparingTo("100.00");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0.004", "0", "-0.01", "-1"})
    @DisplayName("S1-AK-02: Sollbuchung mit Betrag <= 0 nach Rundung → UngueltigerBetrag, nichts gebucht")
    void sollbuchungMitUngueltigemBetrag(String betrag) {
        SollbuchungBefehl befehl =
                new SollbuchungBefehl(Buchungsart.ZINSEN, new BigDecimal(betrag), Testdaten.VALUTA_ZINSEN, "soll-x");

        assertThatThrownBy(() -> buchungService.sollbuchen(az1.getId(), befehl))
                .isInstanceOf(UngueltigerBetragException.class);
        assertThat(anzahl("buchung")).isZero();
        assertThat(saldo(az1.getId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S1-AK-02: Sollbuchung und Zahlung ohne Betrag (null) → UngueltigerBetrag, nichts gebucht")
    void betragNull() {
        SollbuchungBefehl soll = new SollbuchungBefehl(Buchungsart.ZINSEN, null, Testdaten.VALUTA_ZINSEN, "soll-null");
        ZahlungBefehl zahlung = new ZahlungBefehl(d1.getId(), null, Testdaten.VALUTA_ZAHLUNG, "zahlung-null");

        assertThatThrownBy(() -> buchungService.sollbuchen(az1.getId(), soll))
                .isInstanceOf(UngueltigerBetragException.class);
        assertThatThrownBy(() -> buchungService.zahlen(az1.getId(), zahlung))
                .isInstanceOf(UngueltigerBetragException.class);
        assertThat(anzahl("buchung")).isZero();
    }

    @Test
    @DisplayName("S1-AK-02: Zahlung mit Betrag 0.004 → UngueltigerBetrag, nichts gebucht")
    void zahlungMitUngueltigemBetrag() {
        ZahlungBefehl befehl = new ZahlungBefehl(d1.getId(), eur("0.004"), Testdaten.VALUTA_ZAHLUNG, "zahlung-x");

        assertThatThrownBy(() -> buchungService.zahlen(az1.getId(), befehl))
                .isInstanceOf(UngueltigerBetragException.class);
        assertThat(anzahl("buchung")).isZero();
        assertThat(saldo(az1.getId())).isEqualByComparingTo("0.00");
    }
}
