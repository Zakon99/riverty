package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.support.Json;
import tools.jackson.databind.JsonNode;

/**
 * Der Interview-Walkthrough als ein durchgehender Test, ausschliesslich ueber HTTP. Jeder Schritt prueft Status und
 * Zwischenwerte.
 */
class InterviewFlowTest extends ApiTestBasis {

    @Test
    @DisplayName("S2-AK-36: Interview-Flow Kreditor → Debitoren → Konto → Sollbuchungen → Zahlungen → Verrechnung → Storno → Konsistenz")
    void interviewFlow() throws Exception {
        // (1) Kreditor
        long kreditorId = idAus(postJson("/api/kreditoren", kreditorJson("K-1001", "Inkasso AG"))
                .andExpect(jsonPath("$.kreditorennummer").value("K-1001")), 201);

        // (2) Debitoren D1, D2
        long d1 = idAus(postJson("/api/debitoren", debitorJson("Max", "Mustermann", "1985-03-14", "10115")), 201);
        long d2 = idAus(postJson("/api/debitoren", debitorJson("Erika", "Mustermann", "1987-08-22", "10115")), 201);
        assertThat(d1).isNotEqualTo(d2);

        // (3) Konto mit D1, D2 zuordnen
        long kontoId = idAus(postJson("/api/konten", kontoJson("AZ-INTERVIEW", kreditorId, d1))
                .andExpect(jsonPath("$.debitorIds[0]").value(d1)), 201);
        postJson(konto(kontoId) + "/debitoren", zuordnungJson(d2, "sachbearbeiter1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitorId").value(d2));
        JsonNode ansicht = kontoansicht(kontoId);
        assertThat(ids(ansicht.path("debitoren"), "id")).containsExactlyInAnyOrder(d1, d2);
        Json.assertBetrag(ansicht.path("saldo"), "0.00");

        // (4) HF 1000 (2026-01-10), ZINSEN 50 (2026-02-01), GEB 30 (2026-02-01)
        long hf = soll(kontoId, "HAUPTFORDERUNG", "1000", "2026-01-10");
        long zinsen = soll(kontoId, "ZINSEN", "50", "2026-02-01");
        long geb = soll(kontoId, "GEBUEHREN", "30", "2026-02-01");
        assertThat(saldo(kontoId)).isEqualByComparingTo("1080.00");
        assertThat(ids(offenePosten(kontoId), "buchungId")).containsExactly(geb, zinsen, hf);

        // (5) D1 zahlt 100 → Saldo 980, offen nur HF 980
        long zahlungD1 = zahlung(kontoId, d1, "100", "2026-03-01");
        assertThat(saldo(kontoId)).isEqualByComparingTo("980.00");
        JsonNode offen = offenePosten(kontoId);
        assertThat(ids(offen, "buchungId")).containsExactly(hf);
        Json.assertBetrag(offen.get(0).path("offen"), "980.00");
        JsonNode verrechnungD1 = verrechnungen(kontoId);
        assertThat(verrechnungD1.size()).isEqualTo(3);
        Json.assertBetrag(eintragMit(verrechnungD1, "sollBuchungId", geb).path("betrag"), "30.00");
        Json.assertBetrag(eintragMit(verrechnungD1, "sollBuchungId", zinsen).path("betrag"), "50.00");
        Json.assertBetrag(eintragMit(verrechnungD1, "sollBuchungId", hf).path("betrag"), "20.00");

        // (6) D2 zahlt 1000 → Saldo -20, D2 Guthaben 20
        long zahlungD2 = zahlung(kontoId, d2, "1000", "2026-03-15");
        ansicht = kontoansicht(kontoId);
        Json.assertBetrag(ansicht.path("saldo"), "-20.00");
        Json.assertBetrag(debitorEintrag(ansicht, d2).path("guthaben"), "20.00");
        Json.assertBetrag(debitorEintrag(ansicht, d1).path("guthaben"), "0.00");
        assertThat(offenePosten(kontoId).size()).isZero();

        // (7) ZINSEN 15 → Saldo -5 (keine automatische Verrechnung mit Altguthaben)
        long zinsen2 = soll(kontoId, "ZINSEN", "15", "2026-04-01");
        assertThat(saldo(kontoId)).isEqualByComparingTo("-5.00");
        assertThat(ids(offenePosten(kontoId), "buchungId")).containsExactly(zinsen2);
        assertThat(guthaben(kontoId, d2)).isEqualByComparingTo("20.00");

        // (8) POST verrechnung → eine Verrechnung 15, D2 Guthaben 5
        JsonNode neu = json(postOhneBody(verrechnung(kontoId)).andExpect(status().isOk()));
        assertThat(neu.size()).isEqualTo(1);
        assertThat(neu.get(0).path("habenBuchungId").asLong()).isEqualTo(zahlungD2);
        assertThat(neu.get(0).path("sollBuchungId").asLong()).isEqualTo(zinsen2);
        Json.assertBetrag(neu.get(0).path("betrag"), "15.00");
        ansicht = kontoansicht(kontoId);
        Json.assertBetrag(ansicht.path("saldo"), "-5.00");
        Json.assertBetrag(debitorEintrag(ansicht, d2).path("guthaben"), "5.00");
        assertThat(offenePosten(kontoId).size()).isZero();

        // (9) Storno Zahlung D1 (RUECKLASTSCHRIFT) → Saldo 95, offen GEB 30, ZINSEN 50, HF 20
        long storno = idAus(postJson(storno(kontoId, zahlungD1), stornoJson("RUECKLASTSCHRIFT", "bank-import", "s-1"))
                .andExpect(jsonPath("$.art").value("STORNO"))
                .andExpect(jsonPath("$.stornoVonId").value(zahlungD1))
                .andExpect(jsonPath("$.valuta").value("2026-03-01")), 201);
        assertThat(saldo(kontoId)).isEqualByComparingTo("95.00");
        offen = offenePosten(kontoId);
        assertThat(ids(offen, "buchungId")).containsExactly(geb, zinsen, hf);
        Json.assertBetrag(offen.get(0).path("offen"), "30.00");
        Json.assertBetrag(offen.get(1).path("offen"), "50.00");
        Json.assertBetrag(offen.get(2).path("offen"), "20.00");
        assertThat(guthaben(kontoId, d2)).isEqualByComparingTo("5.00");
        JsonNode buchungen = kontoansicht(kontoId).path("buchungen");
        assertThat(ids(buchungen, "id")).contains(storno, zahlungD1);

        // (10) Konsistenz OK
        getUrl(konto(kontoId) + "/konsistenz")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.abweichungen").isArray())
                .andExpect(jsonPath("$.abweichungen.length()").value(0));
    }
}
