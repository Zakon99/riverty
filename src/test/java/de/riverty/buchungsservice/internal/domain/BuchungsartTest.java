package de.riverty.buchungsservice.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BuchungsartTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Buchungsart.class, names = {"HAUPTFORDERUNG", "ZINSEN", "GEBUEHREN"})
    @DisplayName("S1-AK-13: HAUPTFORDERUNG, ZINSEN, GEBUEHREN sind Sollarten")
    void sollarten(Buchungsart art) {
        assertThat(art.istSollart()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Buchungsart.class, names = {"ZAHLUNG", "AUSBUCHUNG", "STORNO"})
    @DisplayName("S1-AK-15: ZAHLUNG, AUSBUCHUNG, STORNO sind keine Sollarten")
    void keineSollarten(Buchungsart art) {
        assertThat(art.istSollart()).isFalse();
    }
}
