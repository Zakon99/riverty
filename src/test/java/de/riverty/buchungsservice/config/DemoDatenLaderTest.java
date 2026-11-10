package de.riverty.buchungsservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.service.BuchungService;
import de.riverty.buchungsservice.internal.service.DebitorService;
import de.riverty.buchungsservice.internal.service.KontoService;
import de.riverty.buchungsservice.internal.service.KreditorService;
import de.riverty.buchungsservice.internal.service.SollbuchungBefehl;
import de.riverty.buchungsservice.support.Datenbank;
import de.riverty.buchungsservice.support.Json;
import de.riverty.buchungsservice.support.TestClockKonfiguration;
import tools.jackson.databind.JsonNode;

/**
 * S2-AK-39/40/46: Demo-Profil. Eigene In-Memory-DB, damit die beim Kontextstart geladenen Demo-Daten nicht vom Leeren
 * der Tabellen in den uebrigen Integrationstests (gemeinsame DB {@code buchungsservice-test}) betroffen sind.
 * <p>
 * Reihenfolgeunabhaengig (S2-AK-46): Der Stand nach dem Start wird einmalig in {@link #startStandMerken} festgehalten,
 * bevor irgendein Test dieser Klasse laeuft; jeder Test stellt seinen Ausgangszustand selbst her (Tabellen leeren,
 * ggf. Teilstand anlegen).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:buchungsservice-demo;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@Import(TestClockKonfiguration.class)
class DemoDatenLaderTest {

    /** Erwarteter Fachstand nach dem Laden der Demo-Daten, in der Darstellung von {@link #stand(JdbcTemplate)}. */
    private static final List<String> DEMO_STAND = List.of(
            "kreditor K-DEMO Musterstadt Versandhandel GmbH",
            "debitor Erika Mustermann 1987-08-22 10115",
            "debitor Max Mustermann 1985-03-14 10115",
            "konto DEMO-0001 1080.00",
            "zuordnung DEMO-0001 Erika demo",
            "zuordnung DEMO-0001 Max Kontoanlage",
            "buchung DEMO-0001 HAUPTFORDERUNG 1000.00 2026-01-15 demo-hauptforderung",
            "buchung DEMO-0001 GEBUEHREN 30.00 2026-02-01 demo-gebuehren",
            "buchung DEMO-0001 ZINSEN 50.00 2026-03-01 demo-zinsen");

    /** Stand unmittelbar nach dem Kontextstart, vor dem ersten Test. */
    private static List<String> startStand;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DemoDatenLader demoDatenLader;
    @Autowired
    private KreditorService kreditorService;
    @Autowired
    private DebitorService debitorService;
    @Autowired
    private KontoService kontoService;
    @Autowired
    private BuchungService buchungService;

    @BeforeAll
    static void startStandMerken(@Autowired JdbcTemplate jdbc) {
        startStand = stand(jdbc);
    }

    @Test
    @DisplayName("S2-AK-39 / S2-AK-46: Profil demo → beim Start Kreditor K-DEMO, Max und Erika Mustermann, Konto DEMO-0001 mit 3 Sollbuchungen, Saldo 1080.00, keine Zahlung")
    void demoDatenNachStart() {
        assertThat(startStand).containsExactlyElementsOf(DEMO_STAND);
    }

    @Test
    @DisplayName("S2-AK-39 / S2-AK-46: laden() auf leerer DB → vollstaendiger Demo-Stand, auch ueber die API sichtbar")
    void ladenAufLeererDb() throws Exception {
        Datenbank.leeren(jdbc);

        demoDatenLader.laden();

        pruefeDemoStand();
    }

    @Test
    @DisplayName("S2-AK-40 / S2-AK-46: laden() auf bereits geladenem Stand zweimal erneut aufrufen → kein Fehler, keine Duplikate, 3 Buchungen")
    void ladenIstIdempotent() throws Exception {
        Datenbank.leeren(jdbc);
        demoDatenLader.laden();

        demoDatenLader.laden();
        demoDatenLader.laden();

        pruefeDemoStand();
    }

    @Test
    @DisplayName("S2-AK-40: laden() gegen halb angelegten Stand (Konto mit Max und Hauptforderung) → vervollstaendigt ohne Duplikate")
    void ladenVervollstaendigtHalbenStand() throws Exception {
        Datenbank.leeren(jdbc);
        Kreditor kreditor = kreditorService.anlegen("K-DEMO", "Musterstadt Versandhandel GmbH");
        Long max = debitorService.anlegen("Max", "Mustermann", LocalDate.of(1985, 3, 14), "10115").getId();
        Forderungskonto konto = kontoService.anlegen("DEMO-0001", kreditor.getId(), max);
        buchungService.sollbuchen(konto.getId(), new SollbuchungBefehl(Buchungsart.HAUPTFORDERUNG,
                new BigDecimal("1000.00"), LocalDate.of(2026, 1, 15), "demo-hauptforderung"));

        demoDatenLader.laden();
        demoDatenLader.laden();

        pruefeDemoStand();
    }

    @Test
    @DisplayName("S2-AK-40: laden() gegen halb angelegten Stand (nur Kreditor und Erika) → vervollstaendigt ohne Duplikate")
    void ladenVervollstaendigtNurStammdaten() throws Exception {
        Datenbank.leeren(jdbc);
        kreditorService.anlegen("K-DEMO", "Musterstadt Versandhandel GmbH");
        debitorService.anlegen("Erika", "Mustermann", LocalDate.of(1987, 8, 22), "10115");

        demoDatenLader.laden();

        pruefeDemoStand();
    }

    /** Fachlicher Stand aller Tabellen, unabhaengig von generierten Ids; Buchungen nach Valuta sortiert. */
    private static List<String> stand(JdbcTemplate jdbc) {
        List<String> zeilen = new ArrayList<>();
        zeilen.addAll(jdbc.query("SELECT kreditorennummer, name FROM kreditor ORDER BY kreditorennummer",
                (rs, i) -> "kreditor " + rs.getString(1) + " " + rs.getString(2)));
        zeilen.addAll(jdbc.query("SELECT vorname, nachname, geburtsdatum, plz FROM debitor ORDER BY vorname",
                (rs, i) -> "debitor " + rs.getString(1) + " " + rs.getString(2) + " "
                        + rs.getObject(3, LocalDate.class) + " " + rs.getString(4)));
        zeilen.addAll(jdbc.query("SELECT aktenzeichen, saldo FROM forderungskonto ORDER BY aktenzeichen",
                (rs, i) -> "konto " + rs.getString(1) + " " + rs.getBigDecimal(2).setScale(2).toPlainString()));
        zeilen.addAll(jdbc.query("SELECT k.aktenzeichen, d.vorname, z.zugeordnet_von FROM konto_debitor z "
                        + "JOIN forderungskonto k ON k.id = z.konto_id JOIN debitor d ON d.id = z.debitor_id "
                        + "ORDER BY k.aktenzeichen, d.vorname",
                (rs, i) -> "zuordnung " + rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3)));
        zeilen.addAll(jdbc.query("SELECT k.aktenzeichen, b.art, b.betrag, b.valuta, b.client_request_id "
                        + "FROM buchung b JOIN forderungskonto k ON k.id = b.konto_id "
                        + "ORDER BY k.aktenzeichen, b.valuta, b.id",
                (rs, i) -> "buchung " + rs.getString(1) + " " + rs.getString(2) + " "
                        + rs.getBigDecimal(3).setScale(2).toPlainString() + " " + rs.getObject(4, LocalDate.class)
                        + " " + rs.getString(5)));
        return zeilen;
    }

    private void pruefeDemoStand() throws Exception {
        assertThat(stand(jdbc)).containsExactlyElementsOf(DEMO_STAND);
        assertThat(Datenbank.anzahl(jdbc, "kreditor")).isEqualTo(1);
        assertThat(Datenbank.anzahl(jdbc, "debitor")).isEqualTo(2);
        assertThat(Datenbank.anzahl(jdbc, "forderungskonto")).isEqualTo(1);
        assertThat(Datenbank.anzahl(jdbc, "konto_debitor")).isEqualTo(2);
        assertThat(Datenbank.anzahl(jdbc, "buchung")).isEqualTo(3);

        JsonNode treffer = lesen("/api/konten?aktenzeichen=DEMO-0001");
        assertThat(treffer.size()).isEqualTo(1);
        long kontoId = treffer.get(0).path("id").asLong();
        assertThat(lesen("/api/konten?kreditorennummer=K-DEMO").size()).isEqualTo(1);

        JsonNode ansicht = lesen("/api/konten/" + kontoId);
        assertThat(ansicht.path("aktenzeichen").asString()).isEqualTo("DEMO-0001");
        Json.assertBetrag(ansicht.path("saldo"), "1080.00");
        assertThat(ansicht.path("kreditor").path("kreditorennummer").asString()).isEqualTo("K-DEMO");
        assertThat(ansicht.path("kreditor").path("name").asString()).isEqualTo("Musterstadt Versandhandel GmbH");

        JsonNode debitoren = ansicht.path("debitoren");
        assertThat(debitoren.size()).isEqualTo(2);
        JsonNode max = debitorMitVorname(debitoren, "Max");
        assertThat(max.path("nachname").asString()).isEqualTo("Mustermann");
        assertThat(max.path("geburtsdatum").asString()).isEqualTo("1985-03-14");
        assertThat(max.path("plz").asString()).isEqualTo("10115");
        assertThat(max.path("zugeordnetVon").asString()).as("Max bei Kontoanlage zugeordnet").isEqualTo("Kontoanlage");
        Json.assertBetrag(max.path("guthaben"), "0.00");
        JsonNode erika = debitorMitVorname(debitoren, "Erika");
        assertThat(erika.path("nachname").asString()).isEqualTo("Mustermann");
        assertThat(erika.path("geburtsdatum").asString()).isEqualTo("1987-08-22");
        assertThat(erika.path("plz").asString()).isEqualTo("10115");
        assertThat(erika.path("zugeordnetVon").asString()).isEqualTo("demo");
        Json.assertBetrag(erika.path("guthaben"), "0.00");

        JsonNode buchungen = ansicht.path("buchungen");
        assertThat(buchungen.size()).isEqualTo(3);
        List<String> beschreibung = new ArrayList<>();
        for (JsonNode buchung : buchungen) {
            beschreibung.add(buchung.path("art").asString() + " " + Json.betrag(buchung.path("betrag")).toPlainString()
                    + " " + buchung.path("valuta").asString() + " " + buchung.path("clientRequestId").asString());
        }
        assertThat(beschreibung).containsExactly(
                "HAUPTFORDERUNG 1000.00 2026-01-15 demo-hauptforderung",
                "GEBUEHREN 30.00 2026-02-01 demo-gebuehren",
                "ZINSEN 50.00 2026-03-01 demo-zinsen");
    }

    private JsonNode lesen(String url) throws Exception {
        return Json.lesen(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode debitorMitVorname(JsonNode debitoren, String vorname) {
        for (JsonNode debitor : debitoren) {
            if (vorname.equals(debitor.path("vorname").asString())) {
                return debitor;
            }
        }
        throw new AssertionError("Debitor " + vorname + " fehlt: " + debitoren);
    }
}
