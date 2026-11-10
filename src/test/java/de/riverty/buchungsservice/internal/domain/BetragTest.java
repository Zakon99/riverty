package de.riverty.buchungsservice.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BetragTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"10.005, 10.01", "10.004, 10.00", "0.005, 0.01", "12, 12.00", "99.995, 100.00"})
    @DisplayName("S1-AK-01: Betrag wird HALF_UP auf Scale 2 gerundet (String-Factory)")
    void rundetHalfUpAufScale2AusString(String roh, String erwartet) {
        Betrag betrag = Betrag.of(roh);

        assertThat(betrag.wert()).isEqualByComparingTo(erwartet);
        assertThat(betrag.wert().scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"10.005, 10.01", "10.004, 10.00", "0.005, 0.01"})
    @DisplayName("S1-AK-01: Betrag wird HALF_UP auf Scale 2 gerundet (BigDecimal-Factory und Konstruktor)")
    void rundetHalfUpAufScale2AusBigDecimal(String roh, String erwartet) {
        Betrag ausFactory = Betrag.of(new BigDecimal(roh));
        Betrag ausKonstruktor = new Betrag(new BigDecimal(roh));

        assertThat(ausFactory.wert()).isEqualByComparingTo(erwartet);
        assertThat(ausFactory.wert().scale()).isEqualTo(2);
        assertThat(ausKonstruktor.wert()).isEqualByComparingTo(erwartet);
        assertThat(ausKonstruktor.wert().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("S1-AK-01: 12 und 12.00 ergeben denselben Betrag")
    void gleicheWerteMitUnterschiedlicherScaleSindGleich() {
        assertThat(Betrag.of("12")).isEqualTo(Betrag.of("12.00"));
        assertThat(Betrag.of(new BigDecimal("12"))).isEqualTo(Betrag.of(new BigDecimal("12.000")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0.004", "0", "0.00", "-0.01", "-1"})
    @DisplayName("S1-AK-02: Betrag <= 0 nach Rundung ist ungueltig")
    void nichtPositiveBetraegeSindUngueltig(String roh) {
        assertThatThrownBy(() -> Betrag.of(roh)).isInstanceOf(UngueltigerBetragException.class);
        assertThatThrownBy(() -> Betrag.of(new BigDecimal(roh))).isInstanceOf(UngueltigerBetragException.class);
        assertThatThrownBy(() -> new Betrag(new BigDecimal(roh))).isInstanceOf(UngueltigerBetragException.class);
    }

    @Test
    @DisplayName("S1-AK-02: null ist ein ungueltiger Betrag")
    void nullIstUngueltig() {
        assertThatThrownBy(() -> Betrag.of((BigDecimal) null)).isInstanceOf(UngueltigerBetragException.class);
        assertThatThrownBy(() -> new Betrag(null)).isInstanceOf(UngueltigerBetragException.class);
    }

    @Test
    @DisplayName("S1-AK-51: Groesster Betrag mit 13 Vorkommastellen (9999999999999.99) ist gueltig")
    void hoechsterBetragIstGueltig() {
        assertThat(Betrag.of("9999999999999.99").wert()).isEqualByComparingTo("9999999999999.99");
        assertThat(Betrag.of(new BigDecimal("9999999999999.99")).wert()).isEqualByComparingTo("9999999999999.99");
        assertThat(new Betrag(new BigDecimal("9999999999999.994")).wert()).isEqualByComparingTo("9999999999999.99");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"10000000000000.00", "10000000000000", "9999999999999.995", "123456789012345.67"})
    @DisplayName("S1-AK-51: Betrag mit mehr als 13 Vorkommastellen (auch erst nach Rundung) ist ungueltig")
    void zuGrosserBetragIstUngueltig(String roh) {
        assertThatThrownBy(() -> Betrag.of(roh)).isInstanceOf(UngueltigerBetragException.class);
        assertThatThrownBy(() -> Betrag.of(new BigDecimal(roh))).isInstanceOf(UngueltigerBetragException.class);
        assertThatThrownBy(() -> new Betrag(new BigDecimal(roh))).isInstanceOf(UngueltigerBetragException.class);
    }

    @Test
    @DisplayName("S1-AK-02: UngueltigerBetragException ist eine FachlicheException")
    void exceptionHierarchie() {
        assertThatThrownBy(() -> Betrag.of("0"))
                .isInstanceOf(FachlicheException.class)
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1e1000000000", "1e-1000000000", "-1e1000000000", "9999999999999.995"})
    @Timeout(1)
    @DisplayName("Extreme Exponenten werden vor dem Runden schnell abgelehnt (kein DoS); 9999999999999.995 nach Rundung")
    void extremeGroessenordnungWirdSchnellAbgelehnt(String roh) {
        assertThatThrownBy(() -> Betrag.of(roh)).isInstanceOf(UngueltigerBetragException.class);
        assertThatThrownBy(() -> new Betrag(new BigDecimal(roh))).isInstanceOf(UngueltigerBetragException.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"0.005, 0.01", "0.00500000000000000001, 0.01", "9999999999999.994, 9999999999999.99"})
    @DisplayName("Grenzwerte der Groessenordnungspruefung bleiben gueltig")
    void grenzwerteDerGroessenordnung(String roh, String erwartet) {
        assertThat(Betrag.of(roh).wert()).isEqualByComparingTo(erwartet);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0.0009", "0.004", "0.00499999999999"})
    @DisplayName("Werte unter 0.005 sind ungueltig, auch wenn sie die Groessenordnungspruefung passieren")
    void werteUnterEinemHalbenCentSindUngueltig(String roh) {
        assertThatThrownBy(() -> Betrag.of(roh)).isInstanceOf(UngueltigerBetragException.class);
    }
}
