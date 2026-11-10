package de.riverty.buchungsservice.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stellt die Systemzeit als injizierbare {@link Clock} bereit, damit Tests eine feste Zeit verwenden können.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
