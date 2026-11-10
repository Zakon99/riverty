package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import de.riverty.buchungsservice.support.IntegrationstestBasis;
import de.riverty.buchungsservice.support.Json;
import tools.jackson.databind.JsonNode;

/**
 * Basis der REST-Integrationstests: MockMvc gegen den vollen Kontext (Profil {@code test}, H2 in-memory, Test-Clock).
 * Testdaten werden ausschliesslich ueber HTTP angelegt. Jede Buchung erhaelt eine eindeutige clientRequestId, danach
 * wird die Clock um eine Minute weitergestellt (streng steigende Buchungszeiten).
 */
abstract class ApiTestBasis extends IntegrationstestBasis {

    /** Id, die in keinem Test vergeben wird. */
    static final long UNBEKANNT = 999_999L;
    static final Duration SCHRITT = Duration.ofMinutes(1);
    static final String GEBURTSDATUM = "1980-01-01";
    static final String PLZ = "10115";

    @Autowired
    protected MockMvc mvc;

    private int zaehler;

    // ---------------------------------------------------------------- HTTP

    ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions postOhneBody(String url) throws Exception {
        return mvc.perform(post(url));
    }

    ResultActions getUrl(String url) throws Exception {
        return mvc.perform(get(url));
    }

    /** Sendet die URL unverändert, also ohne die Template-Kodierung von {@code get(String)} (z. B. bereits kodiertes {@code %20}). */
    ResultActions getRoh(String url) throws Exception {
        return mvc.perform(get(URI.create(url)));
    }

    static JsonNode json(ResultActions antwort) throws Exception {
        return Json.lesen(text(antwort));
    }

    static String text(ResultActions antwort) throws Exception {
        return antwort.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * S2-AK-33: Fehlerantwort als ProblemDetail ({@code application/problem+json}) mit type, title, status, detail,
     * instance; {@code typ} ist der Teil nach {@code urn:buchungsservice:}.
     */
    static ResultActions assertProblem(ResultActions antwort, int status, String typ) throws Exception {
        return antwort.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:buchungsservice:" + typ))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").isString());
    }

    /** 400 validierung mit einem {@code feldfehler}-Eintrag fuer {@code feld}. */
    static void assertFeldfehler(ResultActions antwort, String feld) throws Exception {
        assertProblem(antwort, 400, "validierung");
        JsonNode feldfehler = json(antwort).path("feldfehler");
        assertThat(feldfehler.isArray()).as("feldfehler ist eine Liste: %s", feldfehler).isTrue();
        List<String> felder = new ArrayList<>();
        for (JsonNode eintrag : feldfehler) {
            felder.add(eintrag.path("feld").asString());
            assertThat(eintrag.path("meldung").isString()).as("meldung gesetzt: %s", eintrag).isTrue();
        }
        assertThat(felder).contains(feld);
    }

    // ---------------------------------------------------------------- Request-Bodies

    static String kreditorJson(String kreditorennummer, String name) {
        return """
                {"kreditorennummer":"%s","name":"%s"}""".formatted(kreditorennummer, name);
    }

    static String debitorJson(String vorname, String nachname, String geburtsdatum, String plz) {
        return """
                {"vorname":"%s","nachname":"%s","geburtsdatum":"%s","plz":"%s"}""".formatted(vorname, nachname,
                geburtsdatum, plz);
    }

    static String kontoJson(String aktenzeichen, long kreditorId, Long debitorId) {
        if (debitorId == null) {
            return """
                    {"aktenzeichen":"%s","kreditorId":%d}""".formatted(aktenzeichen, kreditorId);
        }
        return """
                {"aktenzeichen":"%s","kreditorId":%d,"debitorId":%d}""".formatted(aktenzeichen, kreditorId, debitorId);
    }

    static String zuordnungJson(long debitorId, String zugeordnetVon) {
        return """
                {"debitorId":%d,"zugeordnetVon":"%s"}""".formatted(debitorId, zugeordnetVon);
    }

    /** {@code betrag} wird unveraendert als JSON-Literal eingesetzt (z. B. {@code 10.005} oder {@code "abc"}). */
    static String sollJson(String art, String betrag, String valuta, String clientRequestId) {
        return """
                {"art":"%s","betrag":%s,"waehrung":"EUR","valuta":"%s","clientRequestId":"%s"}""".formatted(art,
                betrag, valuta, clientRequestId);
    }

    static String zahlungJson(long debitorId, String betrag, String valuta, String clientRequestId) {
        return """
                {"debitorId":%d,"betrag":%s,"waehrung":"EUR","valuta":"%s","clientRequestId":"%s"}""".formatted(
                debitorId, betrag, valuta, clientRequestId);
    }

    static String stornoJson(String grund, String ausloeser, String clientRequestId) {
        return """
                {"grund":"%s","ausloeser":"%s","clientRequestId":"%s"}""".formatted(grund, ausloeser,
                clientRequestId);
    }

    static String ausbuchungJson(String grund, String ausloeser, String clientRequestId) {
        return stornoJson(grund, ausloeser, clientRequestId);
    }

    // ---------------------------------------------------------------- Pfade

    static String konto(long kontoId) {
        return "/api/konten/" + kontoId;
    }

    static String sollbuchungen(long kontoId) {
        return konto(kontoId) + "/sollbuchungen";
    }

    static String zahlungen(long kontoId) {
        return konto(kontoId) + "/zahlungen";
    }

    static String storno(long kontoId, long buchungId) {
        return konto(kontoId) + "/buchungen/" + buchungId + "/storno";
    }

    static String ausbuchung(long kontoId) {
        return konto(kontoId) + "/ausbuchung";
    }

    static String verrechnung(long kontoId) {
        return konto(kontoId) + "/verrechnung";
    }

    // ---------------------------------------------------------------- Testdaten ueber HTTP

    String naechsteRequestId() {
        zaehler++;
        return "req-" + zaehler;
    }

    long kreditorAnlegen(String kreditorennummer) throws Exception {
        return idAus(postJson("/api/kreditoren", kreditorJson(kreditorennummer, "Kreditor " + kreditorennummer)), 201);
    }

    long debitorAnlegen(String vorname, String nachname) throws Exception {
        return idAus(postJson("/api/debitoren", debitorJson(vorname, nachname, GEBURTSDATUM, PLZ)), 201);
    }

    long kontoAnlegen(String aktenzeichen, long kreditorId, Long debitorId) throws Exception {
        return idAus(postJson("/api/konten", kontoJson(aktenzeichen, kreditorId, debitorId)), 201);
    }

    void zuordnen(long kontoId, long debitorId, String zugeordnetVon) throws Exception {
        postJson(konto(kontoId) + "/debitoren", zuordnungJson(debitorId, zugeordnetVon)).andExpect(status().isCreated());
    }

    long soll(long kontoId, String art, String betrag, String valuta) throws Exception {
        long id = idAus(postJson(sollbuchungen(kontoId), sollJson(art, betrag, valuta, naechsteRequestId())), 201);
        clock.vorstellen(SCHRITT);
        return id;
    }

    long zahlung(long kontoId, long debitorId, String betrag, String valuta) throws Exception {
        long id = idAus(postJson(zahlungen(kontoId), zahlungJson(debitorId, betrag, valuta, naechsteRequestId())), 201);
        clock.vorstellen(SCHRITT);
        return id;
    }

    long stornieren(long kontoId, long buchungId, String grund) throws Exception {
        long id = idAus(postJson(storno(kontoId, buchungId), stornoJson(grund, "sb-test", naechsteRequestId())), 201);
        clock.vorstellen(SCHRITT);
        return id;
    }

    long ausbuchen(long kontoId) throws Exception {
        long id = idAus(postJson(ausbuchung(kontoId),
                ausbuchungJson("UNEINBRINGLICH", "sb-test", naechsteRequestId())), 201);
        clock.vorstellen(SCHRITT);
        return id;
    }

    static long idAus(ResultActions antwort, int erwarteterStatus) throws Exception {
        antwort.andExpect(status().is(erwarteterStatus));
        JsonNode id = json(antwort).path("id");
        assertThat(id.isIntegralNumber()).as("id gesetzt: %s", id).isTrue();
        return id.longValue();
    }

    // ---------------------------------------------------------------- Lesen ueber HTTP

    JsonNode kontoansicht(long kontoId) throws Exception {
        return json(getUrl(konto(kontoId)).andExpect(status().isOk()));
    }

    JsonNode offenePosten(long kontoId) throws Exception {
        return json(getUrl(konto(kontoId) + "/offene-posten").andExpect(status().isOk()));
    }

    JsonNode verrechnungen(long kontoId) throws Exception {
        return json(getUrl(konto(kontoId) + "/verrechnungen").andExpect(status().isOk()));
    }

    BigDecimal saldo(long kontoId) throws Exception {
        return Json.betrag(kontoansicht(kontoId).path("saldo"));
    }

    /** Guthaben eines Debitors laut Kontoansicht. */
    BigDecimal guthaben(long kontoId, long debitorId) throws Exception {
        return Json.betrag(debitorEintrag(kontoansicht(kontoId), debitorId).path("guthaben"));
    }

    static JsonNode debitorEintrag(JsonNode kontoansicht, long debitorId) {
        for (JsonNode debitor : kontoansicht.path("debitoren")) {
            if (debitor.path("id").asLong() == debitorId) {
                return debitor;
            }
        }
        throw new AssertionError("Debitor " + debitorId + " fehlt in der Kontoansicht: " + kontoansicht);
    }

    static JsonNode eintragMit(JsonNode liste, String feld, long wert) {
        for (JsonNode eintrag : liste) {
            if (eintrag.path(feld).asLong() == wert) {
                return eintrag;
            }
        }
        throw new AssertionError("Kein Eintrag mit " + feld + " = " + wert + ": " + liste);
    }

    /** Offener Betrag eines Sollpostens laut {@code /offene-posten} (0, wenn nicht offen). */
    BigDecimal offen(long kontoId, long buchungId) throws Exception {
        for (JsonNode posten : offenePosten(kontoId)) {
            if (posten.path("buchungId").asLong() == buchungId) {
                return Json.betrag(posten.path("offen"));
            }
        }
        return BigDecimal.ZERO;
    }

    static List<Long> ids(JsonNode liste, String feld) {
        List<Long> werte = new ArrayList<>();
        liste.forEach(eintrag -> werte.add(eintrag.path(feld).asLong()));
        return werte;
    }

    static List<String> texte(JsonNode liste, String feld) {
        List<String> werte = new ArrayList<>();
        liste.forEach(eintrag -> werte.add(eintrag.path(feld).asString()));
        return werte;
    }

    int anzahlBuchungen() {
        return anzahl("buchung");
    }
}
