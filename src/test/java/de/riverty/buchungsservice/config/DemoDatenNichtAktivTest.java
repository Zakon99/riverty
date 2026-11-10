package de.riverty.buchungsservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;

import de.riverty.buchungsservice.support.IntegrationstestBasis;

/**
 * S2-AK-39 (Gegenprobe): Ohne Profil {@code demo} (hier: Testprofil) wird kein Demo-Lader registriert.
 */
class DemoDatenNichtAktivTest extends IntegrationstestBasis {

    @Autowired
    private ApplicationContext kontext;

    @Test
    @DisplayName("S2-AK-39: Im Testprofil keine Demo-Daten: kein DemoDatenLader-Bean, Lader nur unter Profil demo")
    void keinDemoLaderImTestprofil() {
        assertThat(kontext.getBeanNamesForType(DemoDatenLader.class)).isEmpty();
        Profile profil = DemoDatenLader.class.getAnnotation(Profile.class);
        assertThat(profil).as("@Profile am DemoDatenLader").isNotNull();
        assertThat(profil.value()).containsExactly("demo");
    }
}
