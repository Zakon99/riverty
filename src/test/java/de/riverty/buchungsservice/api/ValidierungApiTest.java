package de.riverty.buchungsservice.api;

import static de.riverty.buchungsservice.support.Json.mit;
import static de.riverty.buchungsservice.support.Json.ohne;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import de.riverty.buchungsservice.support.MutableClock;

/**
 * Bean Validation und Fehlerformat. Pfade enthalten die Platzhalter {@code {konto}}, {@code {debitor}},
 * {@code {buchung}}, die vor jedem Aufruf durch die Ids der im {@link BeforeEach} angelegten Daten ersetzt werden.
 */
class ValidierungApiTest extends ApiTestBasis {

    private static final String SOLL = "/api/konten/{konto}/sollbuchungen";
    private static final String ZAHLUNG = "/api/konten/{konto}/zahlungen";
    private static final String STORNO = "/api/konten/{konto}/buchungen/{buchung}/storno";
    private static final String AUSBUCHUNG = "/api/konten/{konto}/ausbuchung";
    private static final String ZUORDNUNG = "/api/konten/{konto}/debitoren";

    private static final String SOLL_GUELTIG = sollJson("ZINSEN", "10.00", "2026-02-01", "v-1");
    private static final String ZAHLUNG_GUELTIG = """
            {"debitorId":"{debitor}","betrag":10.00,"waehrung":"EUR","valuta":"2026-03-01","clientRequestId":"v-1"}""";
    private static final String STORNO_GUELTIG = stornoJson("FEHLBUCHUNG", "sb1", "v-1");
    private static final String AUSBUCHUNG_GUELTIG = ausbuchungJson("UNEINBRINGLICH", "sb1", "v-1");
    private static final String KREDITOR_GUELTIG = kreditorJson("K-9", "Neu AG");
    private static final String DEBITOR_GUELTIG = debitorJson("Nina", "Neu", "1990-01-01", "20095");
    private static final String KONTO_GUELTIG = """
            {"aktenzeichen":"AZ-NEU","kreditorId":"{kreditor}"}""";
    private static final String ZUORDNUNG_GUELTIG = """
            {"debitorId":"{debitor2}","zugeordnetVon":"sb1"}""";

    private long kreditorId;
    private long debitorId;
    private long debitor2Id;
    private long kontoId;
    private long buchungId;

    @BeforeEach
    void datenAnlegen() throws Exception {
        kreditorId = kreditorAnlegen("K-1001");
        debitorId = debitorAnlegen("Dora", "Eins");
        debitor2Id = debitorAnlegen("Dieter", "Zwei");
        kontoId = kontoAnlegen("AZ-1", kreditorId, debitorId);
        buchungId = soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-01-10");
    }

    /** Ersetzt Platzhalter; Id-Platzhalter in Bodies stehen in Anfuehrungszeichen und werden zu JSON-Zahlen. */
    private String ersetzen(String text) {
        return text.replace("\"{kreditor}\"", String.valueOf(kreditorId))
                .replace("\"{debitor}\"", String.valueOf(debitorId))
                .replace("\"{debitor2}\"", String.valueOf(debitor2Id))
                .replace("{konto}", String.valueOf(kontoId))
                .replace("{buchung}", String.valueOf(buchungId));
    }

    private ResultActions senden(String pfad, String body) throws Exception {
        return postJson(ersetzen(pfad), ersetzen(body));
    }

    // ---------------------------------------------------------------- S2-AK-29

    static Stream<Arguments> ungueltigeBetraege() {
        return Stream.of(
                Arguments.of("0", "betrag"),
                Arguments.of("-5", "betrag"),
                Arguments.of("0.004", "betrag"),
                Arguments.of("null", "betrag"),
                Arguments.of("\"abc\"", null),
                Arguments.of("10000000000000.00", "betrag"));
    }

    @ParameterizedTest(name = "Sollbuchung betrag {0}")
    @MethodSource("ungueltigeBetraege")
    @DisplayName("S2-AK-29: Ungueltiger Betrag bei Sollbuchung → 400, keine Buchung; dieselbe clientRequestId danach gueltig → 201")
    void ungueltigerBetragSollbuchung(String betrag, String feld) throws Exception {
        int buchungen = anzahlBuchungen();

        ResultActions antwort = postJson(sollbuchungen(kontoId), sollJson("ZINSEN", betrag, "2026-02-01", "betrag-1"));

        pruefeBetragFehler(antwort, feld);
        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "10.00", "2026-02-01", "betrag-1"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "Zahlung betrag {0}")
    @MethodSource("ungueltigeBetraege")
    @DisplayName("S2-AK-29: Ungueltiger Betrag bei Zahlung → 400, keine Buchung; dieselbe clientRequestId danach gueltig → 201")
    void ungueltigerBetragZahlung(String betrag, String feld) throws Exception {
        int buchungen = anzahlBuchungen();

        ResultActions antwort = postJson(zahlungen(kontoId), zahlungJson(debitorId, betrag, "2026-03-01", "betrag-2"));

        pruefeBetragFehler(antwort, feld);
        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        postJson(zahlungen(kontoId), zahlungJson(debitorId, "10.00", "2026-03-01", "betrag-2"))
                .andExpect(status().isCreated());
    }

    private static void pruefeBetragFehler(ResultActions antwort, String feld) throws Exception {
        if (feld == null) {
            assertProblem(antwort, 400, "validierung");
        } else {
            assertFeldfehler(antwort, feld);
        }
    }

    // ---------------------------------------------------------------- S2-AK-30

    static Stream<Arguments> feldfehler() {
        String heute = LocalDate.ofInstant(MutableClock.START, ZoneOffset.UTC)
                .toString();
        return Stream.of(
                Arguments.of(SOLL, mit(SOLL_GUELTIG, "waehrung", "USD"), "waehrung"),
                Arguments.of(SOLL, mit(SOLL_GUELTIG, "waehrung", "eur"), "waehrung"),
                Arguments.of(SOLL, ohne(SOLL_GUELTIG, "waehrung"), "waehrung"),
                Arguments.of(SOLL, ohne(SOLL_GUELTIG, "valuta"), "valuta"),
                Arguments.of(SOLL, mit(SOLL_GUELTIG, "clientRequestId", "  "), "clientRequestId"),
                Arguments.of(SOLL, ohne(SOLL_GUELTIG, "art"), "art"),
                Arguments.of(ZAHLUNG, mit(ZAHLUNG_GUELTIG, "waehrung", "USD"), "waehrung"),
                Arguments.of(ZAHLUNG, ohne(ZAHLUNG_GUELTIG, "valuta"), "valuta"),
                Arguments.of(ZAHLUNG, mit(ZAHLUNG_GUELTIG, "clientRequestId", ""), "clientRequestId"),
                Arguments.of(ZAHLUNG, ohne(ZAHLUNG_GUELTIG, "debitorId"), "debitorId"),
                Arguments.of("/api/kreditoren", mit(KREDITOR_GUELTIG, "kreditorennummer", " "), "kreditorennummer"),
                Arguments.of("/api/kreditoren", mit(KREDITOR_GUELTIG, "name", ""), "name"),
                Arguments.of("/api/konten", mit(KONTO_GUELTIG, "aktenzeichen", "  "), "aktenzeichen"),
                Arguments.of("/api/konten", ohne(KONTO_GUELTIG, "kreditorId"), "kreditorId"),
                Arguments.of("/api/debitoren", mit(DEBITOR_GUELTIG, "vorname", " "), "vorname"),
                Arguments.of("/api/debitoren", mit(DEBITOR_GUELTIG, "nachname", ""), "nachname"),
                Arguments.of("/api/debitoren", mit(DEBITOR_GUELTIG, "plz", " "), "plz"),
                Arguments.of("/api/debitoren", ohne(DEBITOR_GUELTIG, "plz"), "plz"),
                Arguments.of("/api/debitoren", mit(DEBITOR_GUELTIG, "geburtsdatum", heute), "geburtsdatum"),
                Arguments.of("/api/debitoren", mit(DEBITOR_GUELTIG, "geburtsdatum", "2099-01-01"), "geburtsdatum"),
                Arguments.of("/api/debitoren", ohne(DEBITOR_GUELTIG, "geburtsdatum"), "geburtsdatum"),
                Arguments.of(STORNO, ohne(STORNO_GUELTIG, "grund"), "grund"),
                Arguments.of(STORNO, mit(STORNO_GUELTIG, "ausloeser", " "), "ausloeser"),
                Arguments.of(STORNO, mit(STORNO_GUELTIG, "clientRequestId", " "), "clientRequestId"),
                Arguments.of(AUSBUCHUNG, ohne(AUSBUCHUNG_GUELTIG, "grund"), "grund"),
                Arguments.of(AUSBUCHUNG, mit(AUSBUCHUNG_GUELTIG, "ausloeser", ""), "ausloeser"),
                Arguments.of(AUSBUCHUNG, ohne(AUSBUCHUNG_GUELTIG, "clientRequestId"), "clientRequestId"),
                Arguments.of(ZUORDNUNG, ohne(ZUORDNUNG_GUELTIG, "debitorId"), "debitorId"),
                Arguments.of(ZUORDNUNG, mit(ZUORDNUNG_GUELTIG, "zugeordnetVon", " "), "zugeordnetVon"),
                Arguments.of(ZUORDNUNG, ohne(ZUORDNUNG_GUELTIG, "zugeordnetVon"), "zugeordnetVon"));
    }

    @ParameterizedTest(name = "{0}: Feld {2}")
    @MethodSource("feldfehler")
    @DisplayName("S2-AK-30: Ungueltiges Pflichtfeld → 400 validierung mit feldfehler-Eintrag, nichts angelegt")
    void feldfehlerEintrag(String pfad, String body, String feld) throws Exception {
        int buchungen = anzahlBuchungen();
        int konten = anzahl("forderungskonto");
        int debitoren = anzahl("debitor");
        int kreditoren = anzahl("kreditor");
        int zuordnungen = anzahl("konto_debitor");

        assertFeldfehler(senden(pfad, body), feld);

        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(anzahl("forderungskonto")).isEqualTo(konten);
        assertThat(anzahl("debitor")).isEqualTo(debitoren);
        assertThat(anzahl("kreditor")).isEqualTo(kreditoren);
        assertThat(anzahl("konto_debitor")).isEqualTo(zuordnungen);
    }

    @Test
    @DisplayName("S2-AK-30: Gueltige Referenz-Bodies werden angenommen (Gegenprobe zu den Feldfehler-Faellen)")
    void gueltigeBodies() throws Exception {
        senden(SOLL, SOLL_GUELTIG).andExpect(status().isCreated());
        senden(ZAHLUNG, mit(ZAHLUNG_GUELTIG, "clientRequestId", "v-2")).andExpect(status().isCreated());
        senden(STORNO, mit(STORNO_GUELTIG, "clientRequestId", "v-3")).andExpect(status().isCreated());
        senden(ZUORDNUNG, ZUORDNUNG_GUELTIG).andExpect(status().isCreated());
        senden("/api/kreditoren", KREDITOR_GUELTIG).andExpect(status().isCreated());
        senden("/api/debitoren", DEBITOR_GUELTIG).andExpect(status().isCreated());
        senden("/api/konten", KONTO_GUELTIG).andExpect(status().isCreated());
    }

    static Stream<Arguments> unbekannteEnumWerte() {
        return Stream.of(
                Arguments.of(SOLL, mit(SOLL_GUELTIG, "art", "XYZ")),
                Arguments.of(SOLL, mit(SOLL_GUELTIG, "art", "ZAHLUNG")),
                Arguments.of(SOLL, mit(SOLL_GUELTIG, "art", "STORNO")),
                Arguments.of(STORNO, mit(STORNO_GUELTIG, "grund", "FOO")),
                Arguments.of(AUSBUCHUNG, mit(AUSBUCHUNG_GUELTIG, "grund", "FOO")),
                Arguments.of(AUSBUCHUNG, mit(AUSBUCHUNG_GUELTIG, "grund", "FEHLBUCHUNG")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unbekannteEnumWerte")
    @DisplayName("S2-AK-30: Unbekannter Enum-Wert (auch ZAHLUNG als Sollart) → 400 validierung, keine Buchung")
    void unbekannterEnumWert(String pfad, String body) throws Exception {
        int buchungen = anzahlBuchungen();

        assertProblem(senden(pfad, body), 400, "validierung");

        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{",
            "kein json",
            "{\"art\":\"ZINSEN\",\"betrag\":10.00,\"waehrung\":\"EUR\",\"valuta\":\"01.02.2026\",\"clientRequestId\":\"x\"}",
            "{\"art\":\"ZINSEN\",\"betrag\":10.00,\"waehrung\":\"EUR\",\"valuta\":\"2026-13-01\",\"clientRequestId\":\"x\"}"})
    @DisplayName("S2-AK-30: Unlesbares JSON oder falsches Datumsformat → 400 validierung")
    void unlesbaresJson(String body) throws Exception {
        assertProblem(postJson(sollbuchungen(kontoId), body), 400, "validierung");
        assertThat(anzahlBuchungen()).isEqualTo(1);
    }

    @Test
    @DisplayName("S2-AK-30: Fehlender Body bei Sollbuchung → 400 validierung")
    void fehlenderBody() throws Exception {
        assertProblem(mvc.perform(post(sollbuchungen(kontoId)).contentType(MediaType.APPLICATION_JSON)),
                400, "validierung");
    }

    @Test
    @DisplayName("S2-AK-30: Nicht-numerische Pfad-Id bei schreibenden Endpunkten → 400 validierung")
    void nichtNumerischePfadId() throws Exception {
        assertProblem(postJson("/api/konten/abc/sollbuchungen", SOLL_GUELTIG), 400, "validierung");
        assertProblem(postJson("/api/konten/" + kontoId + "/buchungen/abc/storno", STORNO_GUELTIG), 400, "validierung");
        assertProblem(postOhneBody("/api/konten/abc/verrechnung"), 400, "validierung");
    }

    // ---------------------------------------------------------------- S2-AK-31

    @Test
    @DisplayName("S2-AK-31: Ungueltiger Body an nicht existierendes Konto → 400 (nicht 404)")
    void ungueltigerBodyAnUnbekanntesKonto() throws Exception {
        assertFeldfehler(postJson(sollbuchungen(UNBEKANNT), ohne(SOLL_GUELTIG, "valuta")), "valuta");
        assertFeldfehler(postJson(zahlungen(UNBEKANNT), zahlungJson(debitorId, "0", "2026-03-01", "x")), "betrag");
        assertFeldfehler(postJson(storno(UNBEKANNT, UNBEKANNT), ohne(STORNO_GUELTIG, "grund")), "grund");
        assertFeldfehler(postJson(ausbuchung(UNBEKANNT), mit(AUSBUCHUNG_GUELTIG, "ausloeser", " ")), "ausloeser");
        assertFeldfehler(postJson(konto(UNBEKANNT) + "/debitoren", ohne(ZUORDNUNG_GUELTIG, "debitorId")), "debitorId");
    }

    // ---------------------------------------------------------------- S2-AK-33

    @Test
    @DisplayName("S2-AK-33: Jede Fehlerantwort (400, 404, 409, 422) ist application/problem+json mit type, title, status, detail, instance")
    void problemDetailFormat() throws Exception {
        assertProblem(postJson(sollbuchungen(kontoId), ohne(SOLL_GUELTIG, "valuta")), 400, "validierung");
        assertProblem(getUrl(konto(UNBEKANNT)), 404, "nicht-gefunden");
        assertProblem(postJson("/api/kreditoren", kreditorJson("K-1001", "Doppelt")), 409, "duplikat");
        postJson(sollbuchungen(kontoId), SOLL_GUELTIG).andExpect(status().isCreated());
        assertProblem(postJson(sollbuchungen(kontoId), mit(SOLL_GUELTIG, "betrag", 99)), 409, "konflikt");
        long fremd = debitor2Id;
        assertProblem(postJson(zahlungen(kontoId), zahlungJson(fremd, "10.00", "2026-03-01", "fremd")), 422, "fachregel");
    }

    // ---------------------------------------------------------------- S2-AK-42

    /**
     * Felder mit Laengengrenze laut {@code V1__schema.sql}: Pfad, gueltiger Referenz-Body, Feldname, max. Laenge.
     * Der Feldname im Body ist zugleich der erwartete {@code feldfehler[].feld}.
     */
    static Stream<Arguments> laengenbegrenzteFelder() {
        return Stream.of(
                Arguments.of("/api/kreditoren", KREDITOR_GUELTIG, "kreditorennummer", 50),
                Arguments.of("/api/kreditoren", KREDITOR_GUELTIG, "name", 200),
                Arguments.of("/api/debitoren", DEBITOR_GUELTIG, "vorname", 100),
                Arguments.of("/api/debitoren", DEBITOR_GUELTIG, "nachname", 100),
                Arguments.of("/api/debitoren", DEBITOR_GUELTIG, "plz", 10),
                Arguments.of("/api/konten", KONTO_GUELTIG, "aktenzeichen", 50),
                Arguments.of(ZUORDNUNG, ZUORDNUNG_GUELTIG, "zugeordnetVon", 100),
                Arguments.of(STORNO, STORNO_GUELTIG, "ausloeser", 100),
                Arguments.of(STORNO, STORNO_GUELTIG, "bemerkung", 500),
                Arguments.of(AUSBUCHUNG, AUSBUCHUNG_GUELTIG, "ausloeser", 100),
                Arguments.of(AUSBUCHUNG, AUSBUCHUNG_GUELTIG, "bemerkung", 500),
                Arguments.of(SOLL, SOLL_GUELTIG, "clientRequestId", 100),
                Arguments.of(ZAHLUNG, ZAHLUNG_GUELTIG, "clientRequestId", 100));
    }

    @ParameterizedTest(name = "{0}: {2} mit {3}+1 Zeichen")
    @MethodSource("laengenbegrenzteFelder")
    @DisplayName("S2-AK-42: Zu langer String (max+1) → 400 validierung mit feldfehler fuer das Feld, nichts angelegt")
    void zuLangerString(String pfad, String body, String feld, int max) throws Exception {
        int buchungen = anzahlBuchungen();
        int konten = anzahl("forderungskonto");
        int debitoren = anzahl("debitor");
        int kreditoren = anzahl("kreditor");
        int zuordnungen = anzahl("konto_debitor");

        assertFeldfehler(senden(pfad, mit(body, feld, "x".repeat(max + 1))), feld);

        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(anzahl("forderungskonto")).isEqualTo(konten);
        assertThat(anzahl("debitor")).isEqualTo(debitoren);
        assertThat(anzahl("kreditor")).isEqualTo(kreditoren);
        assertThat(anzahl("konto_debitor")).isEqualTo(zuordnungen);
    }

    @ParameterizedTest(name = "{0}: {2} mit genau {3} Zeichen")
    @MethodSource("laengenbegrenzteFelder")
    @DisplayName("S2-AK-42: String mit genau max. Laenge → 201")
    void stringMitMaximalerLaenge(String pfad, String body, String feld, int max) throws Exception {
        senden(pfad, mit(body, feld, "x".repeat(max))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("S2-AK-42: Debitor mit allen Feldern in max. Laenge (inkl. Umlauten) → 201, Dublettenschluessel passt in die Spalte")
    void debitorMitAllenFeldernMaximal() throws Exception {
        // Umlaute verdoppeln sich bei der Normalisierung (ä → ae) und Trennzeichen bei der Maskierung: worst case.
        String body = debitorJson("ä".repeat(100), "|".repeat(100), "1990-01-01", "\\\\".repeat(10));

        postJson("/api/debitoren", body).andExpect(status().isCreated());

        assertThat(anzahl("debitor")).isEqualTo(3);
    }

    // ---------------------------------------------------------------- S2-AK-43

    @Test
    @DisplayName("S2-AK-43: PUT /api/konten/{id} → 405 als ProblemDetail mit type urn:buchungsservice:anfrage")
    void nichtErlaubteMethode() throws Exception {
        assertProblem(mvc.perform(put(konto(kontoId)).contentType(MediaType.APPLICATION_JSON).content("{}")),
                405, "anfrage");
    }

    @Test
    @DisplayName("S2-AK-43: POST /api/kreditoren mit Content-Type text/plain → 415 als ProblemDetail mit type urn:buchungsservice:anfrage")
    void nichtUnterstuetzterContentType() throws Exception {
        assertProblem(mvc.perform(post("/api/kreditoren").contentType(MediaType.TEXT_PLAIN).content(KREDITOR_GUELTIG)),
                415, "anfrage");
        assertThat(anzahl("kreditor")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- S2-AK-44

    @Test
    @DisplayName("S2-AK-44: @Past nutzt die Clock-Bean: Clock 2000-01-01, geburtsdatum 2010-01-01 → 400; 1999-12-31 → 201")
    void geburtsdatumGegenClock() throws Exception {
        clock.setzen(Instant.parse("2000-01-01T12:00:00Z"));

        assertFeldfehler(postJson("/api/debitoren", mit(DEBITOR_GUELTIG, "geburtsdatum", "2010-01-01")),
                "geburtsdatum");
        assertThat(anzahl("debitor")).isEqualTo(2);

        postJson("/api/debitoren", mit(DEBITOR_GUELTIG, "geburtsdatum", "1999-12-31"))
                .andExpect(status().isCreated());
    }
}
