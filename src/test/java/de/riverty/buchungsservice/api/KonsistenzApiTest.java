package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.support.Json;
import tools.jackson.databind.JsonNode;

class KonsistenzApiTest extends ApiTestBasis {

    /** Kurzfassung des Interview-Flows (S2-AK-36) ueber HTTP; liefert die Konto-Id. */
    private long interviewFlow() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long d1 = debitorAnlegen("Max", "Mustermann");
        long d2 = debitorAnlegen("Erika", "Mustermann");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, d1);
        zuordnen(kontoId, d2, "sachbearbeiter1");
        soll(kontoId, "HAUPTFORDERUNG", "1000", "2026-01-10");
        soll(kontoId, "ZINSEN", "50", "2026-02-01");
        soll(kontoId, "GEBUEHREN", "30", "2026-02-01");
        long zahlungD1 = zahlung(kontoId, d1, "100", "2026-03-01");
        zahlung(kontoId, d2, "1000", "2026-03-15");
        soll(kontoId, "ZINSEN", "15", "2026-04-01");
        postOhneBody(verrechnung(kontoId)).andExpect(status().isOk());
        stornieren(kontoId, zahlungD1, "RUECKLASTSCHRIFT");
        assertThat(saldo(kontoId)).isEqualByComparingTo("95.00");
        return kontoId;
    }

    @Test
    @DisplayName("S2-AK-34: Nach dem Interview-Flow → GET konsistenz 200 {status OK, abweichungen []}")
    void konsistentNachInterviewFlow() throws Exception {
        long kontoId = interviewFlow();

        getUrl(konto(kontoId) + "/konsistenz")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.abweichungen").isArray())
                .andExpect(jsonPath("$.abweichungen.length()").value(0));
    }

    @Test
    @DisplayName("S2-AK-35: Saldo per SQL um 7.50 erhoeht → ABWEICHUNG SALDO mit erwartet, ist, differenz 7.50")
    void saldoAbweichung() throws Exception {
        long kontoId = interviewFlow();
        jdbc.update("UPDATE forderungskonto SET saldo = saldo + 7.50 WHERE id = ?", kontoId);

        JsonNode bericht = json(getUrl(konto(kontoId) + "/konsistenz")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABWEICHUNG")));

        JsonNode abweichungen = bericht.path("abweichungen");
        assertThat(abweichungen.size()).isEqualTo(1);
        JsonNode saldo = abweichungen.get(0);
        assertThat(saldo.path("pruefung").asString()).isEqualTo("SALDO");
        assertThat(saldo.has("buchungId")).as("null-Felder werden weggelassen").isFalse();
        Json.assertBetrag(saldo.path("erwartet"), "95.00");
        Json.assertBetrag(saldo.path("ist"), "102.50");
        Json.assertBetrag(saldo.path("differenz"), "7.50");
    }
}
