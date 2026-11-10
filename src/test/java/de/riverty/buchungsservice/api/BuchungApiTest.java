package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import de.riverty.buchungsservice.support.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

class BuchungApiTest extends ApiTestBasis {

    /** Zustand S2-AK-17 vor der Zahlung: GEB 30, ZINSEN 50, HF 1000 offen, D1 zugeordnet. */
    private record Ausgangskonto(long kreditorId, long d1, long kontoId, long geb, long zinsen, long hf) {
    }

    private Ausgangskonto ausgangskonto() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long d1 = debitorAnlegen("Dora", "Eins");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, d1);
        long hf = soll(kontoId, "HAUPTFORDERUNG", "1000.00", "2026-01-10");
        long zinsen = soll(kontoId, "ZINSEN", "50.00", "2026-02-01");
        long geb = soll(kontoId, "GEBUEHREN", "30.00", "2026-02-01");
        return new Ausgangskonto(kreditorId, d1, kontoId, geb, zinsen, hf);
    }

    /** Konto mit HF 100.00, D1 (bei Anlage) und D2 zugeordnet; D2 zahlt 120.00 → Guthaben D2 20.00. */
    private record GuthabenKonto(long kontoId, long d1, long d2, long hf, long zahlung) {
    }

    private GuthabenKonto guthabenKonto() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long d1 = debitorAnlegen("Dora", "Eins");
        long d2 = debitorAnlegen("Dieter", "Zwei");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, d1);
        zuordnen(kontoId, d2, "sb1");
        long hf = soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-01-10");
        long zahlung = zahlung(kontoId, d2, "120.00", "2026-03-01");
        return new GuthabenKonto(kontoId, d1, d2, hf, zahlung);
    }

    // ---------------------------------------------------------------- C. Sollbuchung & Zahlung

    @Test
    @DisplayName("S2-AK-16: Sollbuchung ZINSEN 10.005 → 201, betrag 10.01, kreditorId des Kontos, buchungszeit = Test-Clock; Saldo +10.01")
    void sollbuchung() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, null);
        Instant jetzt = clock.instant();

        ResultActions antwort = postJson(sollbuchungen(kontoId), """
                {"art":"ZINSEN","betrag":10.005,"waehrung":"EUR","valuta":"2026-02-01","clientRequestId":"r-1"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.kontoId").value(kontoId))
                .andExpect(jsonPath("$.art").value("ZINSEN"))
                .andExpect(jsonPath("$.valuta").value("2026-02-01"))
                .andExpect(jsonPath("$.clientRequestId").value("r-1"))
                .andExpect(jsonPath("$.kreditorId").value(kreditorId))
                .andExpect(jsonPath("$.debitorId").doesNotExist())
                .andExpect(jsonPath("$.stornoVonId").doesNotExist());

        JsonNode body = json(antwort);
        Json.assertBetrag(body.path("betrag"), "10.01");
        assertThat(Instant.parse(body.path("buchungszeit").asString())).isEqualTo(jetzt);
        assertThat(saldo(kontoId)).isEqualByComparingTo("10.01");
    }

    @Test
    @DisplayName("S2-AK-17: Zahlung 100 bei GEB 30, ZINSEN 50, HF 1000 → 201; Verrechnung GEB 30, ZINSEN 50, HF 20; offen nur HF 980.00")
    void zahlungMitVerrechnung() throws Exception {
        Ausgangskonto k = ausgangskonto();

        ResultActions antwort = postJson(zahlungen(k.kontoId()), """
                {"debitorId":%d,"betrag":100,"waehrung":"EUR","valuta":"2026-03-01","clientRequestId":"z-1"}"""
                .formatted(k.d1()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.art").value("ZAHLUNG"))
                .andExpect(jsonPath("$.debitorId").value(k.d1()))
                .andExpect(jsonPath("$.kontoId").value(k.kontoId()))
                .andExpect(jsonPath("$.valuta").value("2026-03-01"))
                .andExpect(jsonPath("$.clientRequestId").value("z-1"))
                .andExpect(jsonPath("$.kreditorId").doesNotExist());
        Json.assertBetrag(json(antwort).path("betrag"), "100.00");
        long zahlung = json(antwort).path("id").asLong();

        JsonNode verrechnungen = verrechnungen(k.kontoId());
        assertThat(verrechnungen.size()).isEqualTo(3);
        assertThat(texte(verrechnungen, "art")).containsOnly("VERRECHNUNG");
        assertThat(ids(verrechnungen, "habenBuchungId")).containsOnly(zahlung);
        assertThat(ids(verrechnungen, "sollBuchungId")).containsExactlyInAnyOrder(k.geb(), k.zinsen(), k.hf());
        Json.assertBetrag(eintragMit(verrechnungen, "sollBuchungId", k.geb()).path("betrag"), "30.00");
        Json.assertBetrag(eintragMit(verrechnungen, "sollBuchungId", k.zinsen()).path("betrag"), "50.00");
        Json.assertBetrag(eintragMit(verrechnungen, "sollBuchungId", k.hf()).path("betrag"), "20.00");
        verrechnungen.forEach(v -> {
            assertThat(v.path("id").isIntegralNumber()).isTrue();
            assertThat(v.path("zeitpunkt").isString()).isTrue();
            assertThat(v.has("aufhebungVonId")).isFalse();
        });

        JsonNode offen = offenePosten(k.kontoId());
        assertThat(offen.size()).isEqualTo(1);
        JsonNode hf = offen.get(0);
        assertThat(hf.path("buchungId").asLong()).isEqualTo(k.hf());
        assertThat(hf.path("art").asString()).isEqualTo("HAUPTFORDERUNG");
        Json.assertBetrag(hf.path("betrag"), "1000.00");
        Json.assertBetrag(hf.path("offen"), "980.00");
        assertThat(hf.path("valuta").asString()).isEqualTo("2026-01-10");
        assertThat(hf.path("buchungszeit").isString()).isTrue();
        assertThat(saldo(k.kontoId())).isEqualByComparingTo("980.00");
    }

    @Test
    @DisplayName("S2-AK-18: Zahlung eines existierenden, nicht zugeordneten Debitors → 422 fachregel, keine Buchung")
    void zahlungNichtZugeordneterDebitor() throws Exception {
        Ausgangskonto k = ausgangskonto();
        long d3 = debitorAnlegen("Doris", "Drei");
        int buchungen = anzahlBuchungen();

        assertProblem(postJson(zahlungen(k.kontoId()), zahlungJson(d3, "100", "2026-03-01", "z-fremd")),
                422, "fachregel");

        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(saldo(k.kontoId())).isEqualByComparingTo("1080.00");
    }

    @Test
    @DisplayName("S2-AK-19: Zahlung mit unbekannter debitorId → 404; unbekanntes Konto mit gueltigem Body → 404")
    void zahlungUnbekannt() throws Exception {
        Ausgangskonto k = ausgangskonto();
        int buchungen = anzahlBuchungen();

        assertProblem(postJson(zahlungen(k.kontoId()), zahlungJson(UNBEKANNT, "100", "2026-03-01", "z-1")),
                404, "nicht-gefunden");
        assertProblem(postJson(zahlungen(UNBEKANNT), zahlungJson(k.d1(), "100", "2026-03-01", "z-2")),
                404, "nicht-gefunden");
        assertProblem(postJson(sollbuchungen(UNBEKANNT), sollJson("ZINSEN", "10", "2026-03-01", "r-2")),
                404, "nicht-gefunden");

        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
    }

    @Test
    @DisplayName("S2-AK-20: 100.00 offen, D2 zahlt 120.00 → 201; D2 Guthaben 20.00, Saldo -20.00, keine offenen Posten")
    void ueberzahlung() throws Exception {
        GuthabenKonto k = guthabenKonto();

        JsonNode ansicht = kontoansicht(k.kontoId());

        Json.assertBetrag(ansicht.path("saldo"), "-20.00");
        Json.assertBetrag(debitorEintrag(ansicht, k.d2()).path("guthaben"), "20.00");
        Json.assertBetrag(debitorEintrag(ansicht, k.d1()).path("guthaben"), "0.00");
        assertThat(offenePosten(k.kontoId()).size()).isZero();
    }

    // ---------------------------------------------------------------- D. Verrechnung, Storno, Ausbuchung

    @Test
    @DisplayName("S2-AK-21: Guthaben D2 20.00, danach ZINSEN 15.00; POST verrechnung ohne Body → 200, eine Verrechnung 15.00")
    void expliziteVerrechnung() throws Exception {
        GuthabenKonto k = guthabenKonto();
        long zinsen = soll(k.kontoId(), "ZINSEN", "15.00", "2026-04-01");

        JsonNode neu = json(postOhneBody(verrechnung(k.kontoId())).andExpect(status().isOk()));

        assertThat(neu.size()).isEqualTo(1);
        JsonNode v = neu.get(0);
        assertThat(v.path("art").asString()).isEqualTo("VERRECHNUNG");
        assertThat(v.path("sollBuchungId").asLong()).isEqualTo(zinsen);
        assertThat(v.path("habenBuchungId").asLong()).isEqualTo(k.zahlung());
        Json.assertBetrag(v.path("betrag"), "15.00");
        assertThat(guthaben(k.kontoId(), k.d2())).isEqualByComparingTo("5.00");
        assertThat(saldo(k.kontoId())).isEqualByComparingTo("-5.00");
        assertThat(offenePosten(k.kontoId()).size()).isZero();
    }

    @Test
    @DisplayName("S2-AK-22: Verrechnung ohne Guthaben, ohne offene Posten oder ein zweites Mal → 200 []")
    void verrechnungOhneWirkung() throws Exception {
        GuthabenKonto k = guthabenKonto();
        soll(k.kontoId(), "ZINSEN", "15.00", "2026-04-01");
        postOhneBody(verrechnung(k.kontoId())).andExpect(status().isOk());
        int verrechnungen = anzahl("verrechnung");

        assertThat(json(postOhneBody(verrechnung(k.kontoId())).andExpect(status().isOk())).size())
                .as("zweiter Aufruf").isZero();
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);

        long kreditorId = kreditorAnlegen("K-2002");
        long d = debitorAnlegen("Olga", "Ohne");
        long ohneGuthaben = kontoAnlegen("AZ-OHNE-GUTHABEN", kreditorId, d);
        soll(ohneGuthaben, "HAUPTFORDERUNG", "100.00", "2026-01-10");
        assertThat(json(postOhneBody(verrechnung(ohneGuthaben)).andExpect(status().isOk())).size()).isZero();

        long ohnePosten = kontoAnlegen("AZ-OHNE-POSTEN", kreditorId, d);
        zahlung(ohnePosten, d, "50.00", "2026-03-01");
        assertThat(json(postOhneBody(verrechnung(ohnePosten)).andExpect(status().isOk())).size()).isZero();
        assertThat(guthaben(ohnePosten, d)).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("S2-AK-23: Storno der Zahlung aus AK-17 → 201 STORNO, 3 AUFHEBUNGen, Posten wieder offen, Saldo +100.00")
    void stornoZahlung() throws Exception {
        Ausgangskonto k = ausgangskonto();
        long zahlung = idAus(postJson(zahlungen(k.kontoId()), zahlungJson(k.d1(), "100", "2026-03-01", "z-1")), 201);
        clock.vorstellen(SCHRITT);
        JsonNode urspruenglich = verrechnungen(k.kontoId());

        ResultActions antwort = postJson(storno(k.kontoId(), zahlung), """
                {"grund":"RUECKLASTSCHRIFT","ausloeser":"bank-import","clientRequestId":"s-1"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.art").value("STORNO"))
                .andExpect(jsonPath("$.stornoVonId").value(zahlung))
                .andExpect(jsonPath("$.valuta").value("2026-03-01"))
                .andExpect(jsonPath("$.grund").value("RUECKLASTSCHRIFT"))
                .andExpect(jsonPath("$.ausloeser").value("bank-import"))
                .andExpect(jsonPath("$.clientRequestId").value("s-1"))
                .andExpect(jsonPath("$.bemerkung").doesNotExist());
        Json.assertBetrag(json(antwort).path("betrag"), "100.00");
        long storno = json(antwort).path("id").asLong();

        JsonNode aufhebungen = filter(verrechnungen(k.kontoId()), "AUFHEBUNG");
        assertThat(aufhebungen.size()).isEqualTo(3);
        assertThat(ids(aufhebungen, "ausgeloestDurchId")).containsOnly(storno);
        assertThat(ids(aufhebungen, "aufhebungVonId")).containsExactlyInAnyOrderElementsOf(ids(urspruenglich, "id"));
        Json.assertBetrag(eintragMit(aufhebungen, "sollBuchungId", k.hf()).path("betrag"), "20.00");

        JsonNode offen = offenePosten(k.kontoId());
        assertThat(ids(offen, "buchungId")).as("§ 367-Reihenfolge").containsExactly(k.geb(), k.zinsen(), k.hf());
        Json.assertBetrag(offen.get(0).path("offen"), "30.00");
        Json.assertBetrag(offen.get(1).path("offen"), "50.00");
        Json.assertBetrag(offen.get(2).path("offen"), "1000.00");
        assertThat(saldo(k.kontoId())).isEqualByComparingTo("1080.00");
    }

    @Test
    @DisplayName("S2-AK-24: Storno-Fehler: Doppelstorno/Storno eines Stornos → 409, fremdes Konto → 422, unbekannt → 404, verrechnete Ausbuchung → 422")
    void stornoFehler() throws Exception {
        Ausgangskonto k = ausgangskonto();
        long zahlung = zahlung(k.kontoId(), k.d1(), "100.00", "2026-03-01");
        long storno = idAus(postJson(storno(k.kontoId(), zahlung), stornoJson("RUECKLASTSCHRIFT", "bank-import", "s-1")),
                201);
        int buchungen = anzahlBuchungen();

        assertProblem(postJson(storno(k.kontoId(), zahlung), stornoJson("RUECKLASTSCHRIFT", "bank-import", "s-2")),
                409, "konflikt");
        assertProblem(postJson(storno(k.kontoId(), storno), stornoJson("FEHLBUCHUNG", "sb1", "s-3")),
                409, "konflikt");

        long anderesKonto = kontoAnlegen("AZ-2", k.kreditorId(), null);
        long fremd = soll(anderesKonto, "HAUPTFORDERUNG", "50.00", "2026-01-10");
        buchungen = anzahlBuchungen();
        assertProblem(postJson(storno(k.kontoId(), fremd), stornoJson("FEHLBUCHUNG", "sb1", "s-4")), 422, "fachregel");
        assertProblem(postJson(storno(k.kontoId(), UNBEKANNT), stornoJson("FEHLBUCHUNG", "sb1", "s-5")),
                404, "nicht-gefunden");
        assertThat(anzahlBuchungen()).isEqualTo(buchungen);

        ausbuchen(anderesKonto);
        buchungen = anzahlBuchungen();
        assertProblem(postJson(storno(anderesKonto, fremd), stornoJson("FEHLBUCHUNG", "sb1", "s-6")), 422, "fachregel");
        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(saldo(anderesKonto)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S2-AK-25: Ausbuchung 95.00 offen → 201 AUSBUCHUNG, valuta = Clock-Datum (UTC), Saldo 0.00")
    void ausbuchung() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long kontoId = kontoAnlegen("AZ-1", kreditorId, null);
        soll(kontoId, "HAUPTFORDERUNG", "80.00", "2026-01-10");
        soll(kontoId, "ZINSEN", "15.00", "2027-01-10");
        // 23:30 UTC ist in Berlin bereits der Folgetag; massgeblich ist UTC.
        clock.setzen(Instant.parse("2026-11-10T23:30:00Z"));
        clock.zoneSetzen(ZoneId.of("Europe/Berlin"));

        ResultActions antwort = postJson(ausbuchung(kontoId), """
                {"grund":"UNEINBRINGLICH","ausloeser":"sb1","clientRequestId":"a-1"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.art").value("AUSBUCHUNG"))
                .andExpect(jsonPath("$.valuta").value("2026-11-10"))
                .andExpect(jsonPath("$.grund").value("UNEINBRINGLICH"))
                .andExpect(jsonPath("$.ausloeser").value("sb1"))
                .andExpect(jsonPath("$.clientRequestId").value("a-1"));
        Json.assertBetrag(json(antwort).path("betrag"), "95.00");

        assertThat(saldo(kontoId)).isEqualByComparingTo("0.00");
        assertThat(offenePosten(kontoId).size()).isZero();
    }

    @Test
    @DisplayName("S2-AK-25: Ausbuchung ohne offene Posten → 422; offene Posten und Guthaben → 422")
    void ausbuchungAbgelehnt() throws Exception {
        long kreditorId = kreditorAnlegen("K-1001");
        long leer = kontoAnlegen("AZ-LEER", kreditorId, null);
        assertProblem(postJson(ausbuchung(leer), ausbuchungJson("UNEINBRINGLICH", "sb1", "a-1")), 422, "fachregel");

        GuthabenKonto k = guthabenKonto2(kreditorId);
        soll(k.kontoId(), "ZINSEN", "15.00", "2026-04-01");
        int buchungen = anzahlBuchungen();

        assertProblem(postJson(ausbuchung(k.kontoId()), ausbuchungJson("UNEINBRINGLICH", "sb1", "a-2")), 422, "fachregel");

        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(saldo(k.kontoId())).isEqualByComparingTo("-5.00");
    }

    private GuthabenKonto guthabenKonto2(long kreditorId) throws Exception {
        long d1 = debitorAnlegen("Gustav", "Guthaben");
        long kontoId = kontoAnlegen("AZ-GUTHABEN", kreditorId, d1);
        long hf = soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-01-10");
        long zahlung = zahlung(kontoId, d1, "120.00", "2026-03-01");
        return new GuthabenKonto(kontoId, d1, d1, hf, zahlung);
    }

    private static JsonNode filter(JsonNode verrechnungen, String art) {
        ArrayNode ergebnis = JsonNodeFactory.instance.arrayNode();
        verrechnungen.forEach(v -> {
            if (art.equals(v.path("art").asString())) {
                ergebnis.add(v);
            }
        });
        return ergebnis;
    }
}
