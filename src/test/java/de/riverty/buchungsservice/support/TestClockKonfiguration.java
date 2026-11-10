package de.riverty.buchungsservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Ersetzt die System-Clock in Integrationstests durch eine verstellbare {@link MutableClock}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockKonfiguration {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
