package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import de.riverty.buchungsservice.support.Json;
import tools.jackson.databind.JsonNode;

class BeteiligteApiTest extends ApiTestBasis {

    @Test
    @DisplayName("S2-AK-01: POST /api/kreditoren → 201, id gesetzt, Kreditorennummer getrimmt")
    void kreditorAnlegen() throws Exception {
        ResultActions antwort = postJson("/api/kreditoren", kreditorJson(" K-1001 ", "Inkasso AG"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.kreditorennummer").value("K-1001"))
                .andExpect(jsonPath("$.name").value("Inkasso AG"));

        assertThat(json(antwort).propertyNames()).containsExactlyInAnyOrder("id", "kreditorennummer", "name");
        assertThat(anzahl("kreditor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S2-AK-02: Kreditorennummer erneut (auch anderer Name) → 409 duplikat mit vorhandeneId")
    void kreditorDuplikat() throws Exception {
        long vorhanden = idAus(postJson("/api/kreditoren", kreditorJson(" K-1001 ", "Inkasso AG")), 201);

        assertProblem(postJson("/api/kreditoren", kreditorJson("K-1001", "Andere GmbH")), 409, "duplikat")
                .andExpect(jsonPath("$.vorhandeneId").value(vorhanden));
        assertThat(anzahl("kreditor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S2-AK-03: POST /api/debitoren → 201, Felder unveraendert, kein dublettenschluessel im Body")
    void debitorAnlegen() throws Exception {
        ResultActions antwort = postJson("/api/debitoren", debitorJson("Jörg", "Müller", "1980-05-17", "10115"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.dublettenschluessel").doesNotExist());

        JsonNode body = json(antwort);
        assertThat(body.path("vorname").asString()).isEqualTo("Jörg");
        assertThat(body.path("nachname").asString()).isEqualTo("Müller");
        assertThat(body.path("geburtsdatum").asString()).isEqualTo("1980-05-17");
        assertThat(body.path("plz").asString()).isEqualTo("10115");
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "vorname", "nachname", "geburtsdatum", "plz");
    }

    @Test
    @DisplayName("S2-AK-04: Dublette (\" joerg \", \"MÜLLER\", gleiches Datum, gleiche PLZ) → 409 mit Debitor-Id")
    void debitorDublette() throws Exception {
        long vorhanden = idAus(postJson("/api/debitoren", debitorJson("Jörg", "Müller", "1980-05-17", "10115")), 201);

        assertProblem(postJson("/api/debitoren", debitorJson(" joerg ", "MÜLLER", "1980-05-17", "10115")),
                409, "duplikat")
                .andExpect(jsonPath("$.vorhandeneId").value(vorhanden));
        assertThat(anzahl("debitor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S2-AK-05: POST /api/konten mit Debitor → 201, kreditor, debitorIds [Y], saldo 0.00; ohne → []")
    void kontoAnlegen() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long debitorId = debitorAnlegen("Dora", "Eins");

        ResultActions mit = postJson("/api/konten", kontoJson("AZ-1", kreditorId, debitorId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.aktenzeichen").value("AZ-1"))
                .andExpect(jsonPath("$.kreditor.id").value(kreditorId))
                .andExpect(jsonPath("$.kreditor.kreditorennummer").value("K-1001"))
                .andExpect(jsonPath("$.kreditor.name").value("Kreditor K-1001"))
                .andExpect(jsonPath("$.debitorIds.length()").value(1))
                .andExpect(jsonPath("$.debitorIds[0]").value(debitorId));
        Json.assertBetrag(json(mit).path("saldo"), "0.00");

        ResultActions ohne = postJson("/api/konten", kontoJson("AZ-2", kreditorId, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aktenzeichen").value("AZ-2"))
                .andExpect(jsonPath("$.debitorIds").isArray())
                .andExpect(jsonPath("$.debitorIds.length()").value(0));
        Json.assertBetrag(json(ohne).path("saldo"), "0.00");
    }

    @Test
    @DisplayName("S2-AK-06: Konto mit unbekanntem Kreditor/Debitor → 404; vorhandenes Aktenzeichen → 409 mit Konto-Id")
    void kontoFehlerfaelle() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long vorhanden = kontoAnlegen("AZ-1", kreditorId, null);

        assertProblem(postJson("/api/konten", kontoJson("AZ-9", UNBEKANNT, null)), 404, "nicht-gefunden");
        assertProblem(postJson("/api/konten", kontoJson("AZ-9", kreditorId, UNBEKANNT)), 404, "nicht-gefunden");
        assertProblem(postJson("/api/konten", kontoJson("AZ-1", kreditorId, null)), 409, "duplikat")
                .andExpect(jsonPath("$.vorhandeneId").value(vorhanden));
        assertThat(anzahl("forderungskonto")).isEqualTo(1);
    }

    @Test
    @DisplayName("S2-AK-07: POST /api/konten/{id}/debitoren → 201 mit zugeordnetAm aus Test-Clock; unbekannt → 404")
    void debitorZuordnen() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, null);
        long debitorId = debitorAnlegen("Zora", "Zett");
        clock.vorstellen(Duration.ofMinutes(5));
        Instant jetzt = clock.instant();

        ResultActions antwort = postJson(konto(kontoId) + "/debitoren", zuordnungJson(debitorId, "sachbearbeiter1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kontoId").value(kontoId))
                .andExpect(jsonPath("$.debitorId").value(debitorId))
                .andExpect(jsonPath("$.zugeordnetVon").value("sachbearbeiter1"));
        assertThat(Instant.parse(json(antwort).path("zugeordnetAm").asString())).isEqualTo(jetzt);

        assertProblem(postJson(konto(UNBEKANNT) + "/debitoren", zuordnungJson(debitorId, "sachbearbeiter1")),
                404, "nicht-gefunden");
        assertProblem(postJson(konto(kontoId) + "/debitoren", zuordnungJson(UNBEKANNT, "sachbearbeiter1")),
                404, "nicht-gefunden");
        assertThat(anzahl("konto_debitor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S2-AK-08: Debitor erneut zuordnen → 409 duplikat, vorhandeneId = Debitor-Id, detail nennt Konto und Debitor")
    void doppelteZuordnung() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        // Zusaetzliche Debitoren, damit Debitor-Id und Zuordnungs-Id auseinanderlaufen.
        debitorAnlegen("Vera", "Vorlauf");
        debitorAnlegen("Volker", "Vorlauf");
        long debitorId = debitorAnlegen("Zora", "Zett");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, null);
        zuordnen(kontoId, debitorId, "sachbearbeiter1");

        ResultActions antwort = assertProblem(
                postJson(konto(kontoId) + "/debitoren", zuordnungJson(debitorId, "sachbearbeiter2")), 409, "duplikat")
                .andExpect(jsonPath("$.vorhandeneId").value(debitorId));

        assertThat(json(antwort).path("detail").asString())
                .contains(String.valueOf(kontoId))
                .contains(String.valueOf(debitorId));
        assertThat(anzahl("konto_debitor")).isEqualTo(1);
    }
}
