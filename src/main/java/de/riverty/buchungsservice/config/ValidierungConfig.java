package de.riverty.buchungsservice.config;

import java.time.Clock;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean Validation nutzt für zeitbezogene Constraints ({@code @Past}, {@code @Future}) die injizierte {@link Clock}
 * statt der Systemzeit.
 */
@Configuration
public class ValidierungConfig {

    @Bean
    public ValidationConfigurationCustomizer uhrFuerValidierung(Clock clock) {
        return konfiguration -> konfiguration.clockProvider(() -> clock);
    }
}
