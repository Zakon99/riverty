package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.riverty.buchungsservice.support.Json;
import tools.jackson.databind.JsonNode;

class KontoSucheUndAnsichtApiTest extends ApiTestBasis {

    private long az1;
    private long az2;
    private long az3;

    /** AZ-1 und AZ-2 bei K-1001, AZ-3 bei K-2002. */
    private void dreiKonten() throws Exception {
        long k1001 = kreditorAnlegen("K-1001");
        long k2002 = kreditorAnlegen("K-2002");
        az1 = kontoAnlegen("AZ-1", k1001, null);
        az2 = kontoAnlegen("AZ-2", k1001, null);
        az3 = kontoAnlegen("AZ-3", k2002, null);
    }

    private ResultActions suche(String aktenzeichen, String kreditorennummer) throws Exception {
        MockHttpServletRequestBuilder anfrage = get("/api/konten");
        if (aktenzeichen != null) {
            anfrage.param("aktenzeichen", aktenzeichen);
        }
        if (kreditorennummer != null) {
            anfrage.param("kreditorennummer", kreditorennummer);
        }
        return mvc.perform(anfrage);
    }

    @Test
    @DisplayName("S2-AK-09: Suche ?aktenzeichen=AZ-2 → 200, genau AZ-2 als KontoResponse")
    void sucheNachAktenzeichen() throws Exception {
        dreiKonten();

        ResultActions antwort = suche("AZ-2", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(az2))
                .andExpect(jsonPath("$[0].aktenzeichen").value("AZ-2"))
                .andExpect(jsonPath("$[0].kreditor.kreditorennummer").value("K-1001"))
                .andExpect(jsonPath("$[0].debitorIds").isArray());
        Json.assertBetrag(json(antwort).get(0).path("saldo"), "0.00");
    }

    @Test
    @DisplayName("S2-AK-10: Suche ?kreditorennummer=K-1001 → 200, [AZ-1, AZ-2] nach Id sortiert")
    void sucheNachKreditorennummer() throws Exception {
        dreiKonten();

        JsonNode treffer = json(suche(null, "K-1001").andExpect(status().isOk()));

        assertThat(ids(treffer, "id")).containsExactly(az1, az2);
        assertThat(texte(treffer, "aktenzeichen")).containsExactly("AZ-1", "AZ-2");
    }

    @Test
    @DisplayName("S2-AK-11: Beide Parameter UND-verknuepft, getrimmt, exakt (case-sensitive); kein Treffer → 200 []")
    void sucheKombiniertUndOhneTreffer() throws Exception {
        dreiKonten();

        assertThat(json(suche("AZ-3", "K-1001").andExpect(status().isOk())).size()).isZero();
        assertThat(ids(json(suche("AZ-1", "K-1001").andExpect(status().isOk())), "id")).containsExactly(az1);
        assertThat(json(suche("AZ-9", null).andExpect(status().isOk())).size()).isZero();
        assertThat(ids(json(suche("  AZ-1 ", null).andExpect(status().isOk())), "id")).containsExactly(az1);
        assertThat(ids(json(suche(null, " K-2002 ").andExpect(status().isOk())), "id")).containsExactly(az3);
        assertThat(json(suche("az-1", null).andExpect(status().isOk())).size()).isZero();
        assertThat(json(suche(null, "k-1001").andExpect(status().isOk())).size()).isZero();
        assertThat(json(suche("AZ", null).andExpect(status().isOk())).size()).as("kein Teiltreffer").isZero();
    }

    @Test
    @DisplayName("S2-AK-12 / S2-AK-45: Suche ohne bzw. mit leeren Parametern → 400 validierung")
    void sucheOhneParameter() throws Exception {
        dreiKonten();

        assertProblem(suche(null, null), 400, "validierung");
        assertProblem(suche("", null), 400, "validierung");
        assertProblem(suche(" ", null), 400, "validierung");
        assertProblem(suche(null, " "), 400, "validierung");
        assertProblem(suche("", " "), 400, "validierung");
        assertProblem(getRoh("/api/konten?aktenzeichen=%20"), 400, "validierung");
    }

    @Test
    @DisplayName("S2-AK-13: GET /api/konten/{id} → saldo, kreditor, debitoren mit Guthaben und Zuordnung, Buchungen inkl. Storno")
    void kontoansicht() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long d1 = debitorAnlegen("Dora", "Eins");
        long d2 = debitorAnlegen("Dieter", "Zwei");
        Instant anlage = clock.instant();
        long kontoId = kontoAnlegen("AZ-1", kreditorId, d1);
        clock.vorstellen(Duration.ofMinutes(5));
        Instant zuordnung = clock.instant();
        zuordnen(kontoId, d2, "sb1");
        clock.vorstellen(SCHRITT);
        long hf = soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-01-10");
        long geb = soll(kontoId, "GEBUEHREN", "10.00", "2026-01-20");
        long stornoGeb = stornieren(kontoId, geb, "FEHLBUCHUNG");
        long zahlung = zahlung(kontoId, d2, "120.00", "2026-03-01");

        JsonNode ansicht = kontoansicht(kontoId);

        assertThat(ansicht.path("id").asLong()).isEqualTo(kontoId);
        assertThat(ansicht.path("aktenzeichen").asString()).isEqualTo("AZ-1");
        Json.assertBetrag(ansicht.path("saldo"), "-20.00");
        assertThat(ansicht.path("kreditor").path("id").asLong()).isEqualTo(kreditorId);
        assertThat(ansicht.path("kreditor").path("kreditorennummer").asString()).isEqualTo("K-1001");
        assertThat(ansicht.path("kreditor").path("name").asString()).isEqualTo("Kreditor K-1001");

        assertThat(ansicht.path("debitoren").size()).isEqualTo(2);
        JsonNode dora = debitorEintrag(ansicht, d1);
        assertThat(dora.path("vorname").asString()).isEqualTo("Dora");
        assertThat(dora.path("nachname").asString()).isEqualTo("Eins");
        assertThat(dora.path("geburtsdatum").asString()).isEqualTo(GEBURTSDATUM);
        assertThat(dora.path("plz").asString()).isEqualTo(PLZ);
        assertThat(dora.path("zugeordnetVon").asString()).isEqualTo("Kontoanlage");
        assertThat(Instant.parse(dora.path("zugeordnetAm").asString())).isEqualTo(anlage);
        Json.assertBetrag(dora.path("guthaben"), "0.00");
        JsonNode dieter = debitorEintrag(ansicht, d2);
        assertThat(dieter.path("zugeordnetVon").asString()).isEqualTo("sb1");
        assertThat(Instant.parse(dieter.path("zugeordnetAm").asString())).isEqualTo(zuordnung);
        Json.assertBetrag(dieter.path("guthaben"), "20.00");

        JsonNode buchungen = ansicht.path("buchungen");
        assertThat(ids(buchungen, "id")).containsExactlyInAnyOrder(hf, geb, stornoGeb, zahlung);
        JsonNode hfEintrag = eintragMit(buchungen, "id", hf);
        assertThat(hfEintrag.path("art").asString()).isEqualTo("HAUPTFORDERUNG");
        assertThat(hfEintrag.path("kontoId").asLong()).isEqualTo(kontoId);
        assertThat(hfEintrag.path("kreditorId").asLong()).isEqualTo(kreditorId);
        assertThat(hfEintrag.has("debitorId")).as("null-Felder werden weggelassen").isFalse();
        assertThat(hfEintrag.has("stornoVonId")).isFalse();
        Json.assertBetrag(hfEintrag.path("betrag"), "100.00");
        assertThat(hfEintrag.path("valuta").asString()).isEqualTo("2026-01-10");
        assertThat(hfEintrag.path("clientRequestId").asString()).isNotBlank();
        assertThat(hfEintrag.path("buchungszeit").isString()).isTrue();
        JsonNode stornoEintrag = eintragMit(buchungen, "id", stornoGeb);
        assertThat(stornoEintrag.path("art").asString()).isEqualTo("STORNO");
        assertThat(stornoEintrag.path("stornoVonId").asLong()).isEqualTo(geb);
        assertThat(stornoEintrag.path("grund").asString()).isEqualTo("FEHLBUCHUNG");
        Json.assertBetrag(stornoEintrag.path("betrag"), "10.00");
        JsonNode zahlungEintrag = eintragMit(buchungen, "id", zahlung);
        assertThat(zahlungEintrag.path("art").asString()).isEqualTo("ZAHLUNG");
        assertThat(zahlungEintrag.path("debitorId").asLong()).isEqualTo(d2);
        assertThat(zahlungEintrag.has("kreditorId")).isFalse();
    }

    @Test
    @DisplayName("S2-AK-14: Historie sortiert nach Valuta, Buchungszeit, Id (aufsteigend)")
    void historieSortierung() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, null);
        long b1 = soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-03-01");
        long b2 = soll(kontoId, "ZINSEN", "10.00", "2026-01-15");
        long b3 = soll(kontoId, "GEBUEHREN", "5.00", "2026-01-15");
        // B4 und B5: gleiche Valuta und gleiche Buchungszeit → kleinere Id zuerst.
        long b4 = idAus(postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "1.00", "2026-02-01", "gleichzeitig-1")), 201);
        long b5 = idAus(postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "2.00", "2026-02-01", "gleichzeitig-2")), 201);

        JsonNode buchungen = kontoansicht(kontoId).path("buchungen");

        assertThat(b4).isLessThan(b5);
        assertThat(ids(buchungen, "id")).containsExactly(b2, b3, b4, b5, b1);
    }

    @Test
    @DisplayName("S2-AK-15: Unbekanntes Konto bei Ansicht, offene-posten, verrechnungen, konsistenz → 404; Id 'abc' → 400")
    void unbekanntesKonto() throws Exception {
        assertProblem(getUrl(konto(UNBEKANNT)), 404, "nicht-gefunden");
        assertProblem(getUrl(konto(UNBEKANNT) + "/offene-posten"), 404, "nicht-gefunden");
        assertProblem(getUrl(konto(UNBEKANNT) + "/verrechnungen"), 404, "nicht-gefunden");
        assertProblem(getUrl(konto(UNBEKANNT) + "/konsistenz"), 404, "nicht-gefunden");
        assertProblem(getUrl("/api/konten/abc"), 400, "validierung");
    }
}
