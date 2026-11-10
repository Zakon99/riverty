package de.riverty.buchungsservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/** Titel und Beschreibung der OpenAPI-Dokumentation ({@code /v3/api-docs}, {@code /swagger-ui.html}). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI buchungsserviceApi() {
        return new OpenAPI().info(new Info()
                .title("Buchungsservice")
                .version("v1")
                .description("Forderungskonten mit Soll- und Habenbuchungen, Verrechnung nach § 367 BGB. "
                        + "Fehler als ProblemDetail (RFC 9457)."));
    }
}
