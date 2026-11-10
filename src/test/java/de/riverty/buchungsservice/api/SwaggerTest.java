package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;

class SwaggerTest extends ApiTestBasis {

    /** Alle 14 Endpunkte der Aufgabe; Pfadvariablen normalisiert zu {@code {}} (Namen sind frei waehlbar). */
    private static final List<String> ENDPUNKTE = List.of(
            "post /api/kreditoren",
            "post /api/debitoren",
            "post /api/konten",
            "post /api/konten/{}/debitoren",
            "get /api/konten",
            "get /api/konten/{}",
            "get /api/konten/{}/offene-posten",
            "get /api/konten/{}/verrechnungen",
            "post /api/konten/{}/sollbuchungen",
            "post /api/konten/{}/zahlungen",
            "post /api/konten/{}/buchungen/{}/storno",
            "post /api/konten/{}/ausbuchung",
            "post /api/konten/{}/verrechnung",
            "get /api/konten/{}/konsistenz");

    @Test
    @DisplayName("S2-AK-41: GET /v3/api-docs → 200 mit allen 14 Endpunkten")
    void apiDocs() throws Exception {
        JsonNode doku = json(getUrl("/v3/api-docs").andExpect(status().isOk()));

        Set<String> vorhanden = new HashSet<>();
        JsonNode pfade = doku.path("paths");
        for (String pfad : pfade.propertyNames()) {
            String normalisiert = pfad.replaceAll("\\{[^}]+}", "{}");
            for (String methode : pfade.path(pfad).propertyNames()) {
                vorhanden.add(methode.toLowerCase() + " " + normalisiert);
            }
        }

        assertThat(ENDPUNKTE).hasSize(14);
        assertThat(vorhanden).containsAll(ENDPUNKTE);
    }

    @Test
    @DisplayName("S2-AK-41: GET /swagger-ui.html → 200 bzw. Redirect auf /swagger-ui/index.html (200)")
    void swaggerUi() throws Exception {
        MockHttpServletResponse antwort = getUrl("/swagger-ui.html").andReturn().getResponse();

        if (antwort.getStatus() != 200) {
            assertThat(antwort.getStatus()).isBetween(300, 399);
            assertThat(antwort.getRedirectedUrl()).contains("/swagger-ui/index.html");
        }
        getUrl("/swagger-ui/index.html").andExpect(status().isOk());
    }
}
