package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.KonfliktException;
import de.riverty.buchungsservice.internal.service.BuchungService;
import de.riverty.buchungsservice.internal.service.BuchungsErgebnis;
import de.riverty.buchungsservice.internal.service.KontoService;
import de.riverty.buchungsservice.internal.service.KreditorService;
import de.riverty.buchungsservice.internal.service.SollbuchungBefehl;
import de.riverty.buchungsservice.support.Json;
import tools.jackson.databind.JsonNode;

class IdempotenzApiTest extends ApiTestBasis {

    @Autowired
    private BuchungService buchungService;
    @Autowired
    private KreditorService kreditorService;
    @Autowired
    private KontoService kontoService;

    private long kreditorId;
    private long d1;
    private long kontoId;

    private void konto() throws Exception {
        kreditorId = kreditorAnlegen("K-1001");
        d1 = debitorAnlegen("Dora", "Eins");
        kontoId = kontoAnlegen("AZ-1", kreditorId, d1);
    }

    @Test
    @DisplayName("S2-AK-26: Identische Sollbuchung erneut (12 statt 12.00) → 200, gleiche Buchung, genau eine Buchung, Saldo unveraendert")
    void replaySollbuchung() throws Exception {
        konto();
        JsonNode original = json(postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "12.00", "2026-02-01", "r-1"))
                .andExpect(status().isCreated()));
        clock.vorstellen(SCHRITT);

        JsonNode replay = json(postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "12", "2026-02-01", "r-1"))
                .andExpect(status().isOk()));

        assertThat(replay).isEqualTo(original);
        Json.assertBetrag(replay.path("betrag"), "12.00");
        assertThat(anzahlBuchungen()).isEqualTo(1);
        assertThat(saldo(kontoId)).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("S2-AK-26: Replay einer Zahlung → 200 mit Original, keine weitere Buchung oder Verrechnung")
    void replayZahlung() throws Exception {
        konto();
        soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-01-10");
        JsonNode original = json(postJson(zahlungen(kontoId), zahlungJson(d1, "40.00", "2026-03-01", "z-1"))
                .andExpect(status().isCreated()));
        clock.vorstellen(SCHRITT);
        int buchungen = anzahlBuchungen();
        int verrechnungen = anzahl("verrechnung");

        JsonNode replay = json(postJson(zahlungen(kontoId), zahlungJson(d1, "40", "2026-03-01", "z-1"))
                .andExpect(status().isOk()));

        assertThat(replay).isEqualTo(original);
        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(kontoId)).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("S2-AK-26: Replay eines Stornos nach dem Storno → 200 mit Original (kein 409 Doppelstorno)")
    void replayStorno() throws Exception {
        konto();
        long hf = soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-01-10");
        JsonNode original = json(postJson(storno(kontoId, hf), stornoJson("FEHLBUCHUNG", "sb1", "s-1"))
                .andExpect(status().isCreated()));
        clock.vorstellen(SCHRITT);
        int buchungen = anzahlBuchungen();

        JsonNode replay = json(postJson(storno(kontoId, hf), stornoJson("FEHLBUCHUNG", "sb1", "s-1"))
                .andExpect(status().isOk()));

        assertThat(replay).isEqualTo(original);
        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(saldo(kontoId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S2-AK-26: Replay einer Ausbuchung → 200 mit Original")
    void replayAusbuchung() throws Exception {
        konto();
        soll(kontoId, "HAUPTFORDERUNG", "100.00", "2026-01-10");
        JsonNode original = json(postJson(ausbuchung(kontoId), ausbuchungJson("UNEINBRINGLICH", "sb1", "a-1"))
                .andExpect(status().isCreated()));
        clock.vorstellen(SCHRITT);
        int buchungen = anzahlBuchungen();

        JsonNode replay = json(postJson(ausbuchung(kontoId), ausbuchungJson("UNEINBRINGLICH", "sb1", "a-1"))
                .andExpect(status().isOk()));

        assertThat(replay).isEqualTo(original);
        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(saldo(kontoId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S2-AK-27: Gleiche clientRequestId mit anderem Betrag, anderer Art, anderem Konto oder anderer Operation → 409 konflikt")
    void gleicheIdAndererInhalt() throws Exception {
        konto();
        long anderesKonto = kontoAnlegen("AZ-2", kreditorId, d1);
        postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "12.00", "2026-02-01", "r-1")).andExpect(status().isCreated());
        int buchungen = anzahlBuchungen();

        assertProblem(postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "12.01", "2026-02-01", "r-1")), 409, "konflikt");
        assertProblem(postJson(sollbuchungen(kontoId), sollJson("GEBUEHREN", "12.00", "2026-02-01", "r-1")),
                409, "konflikt");
        assertProblem(postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "12.00", "2026-02-02", "r-1")), 409, "konflikt");
        assertProblem(postJson(sollbuchungen(anderesKonto), sollJson("ZINSEN", "12.00", "2026-02-01", "r-1")),
                409, "konflikt");
        assertProblem(postJson(zahlungen(kontoId), zahlungJson(d1, "12.00", "2026-02-01", "r-1")), 409, "konflikt");
        assertProblem(postJson(ausbuchung(kontoId), ausbuchungJson("UNEINBRINGLICH", "sb1", "r-1")), 409, "konflikt");

        assertThat(anzahlBuchungen()).isEqualTo(buchungen);
        assertThat(saldo(kontoId)).isEqualByComparingTo("12.00");
        assertThat(saldo(anderesKonto)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S2-AK-28: Parallel gleiche clientRequestId an zwei verschiedene Konten → genau eine Buchung, der andere Aufruf Konflikt")
    void parallelGleicheIdVerschiedeneKonten() throws Exception {
        // Bewusst komplett auf Service-Ebene (Vertrag Stufe 2), unabhaengig von der REST-Schicht.
        Long kreditor = kreditorService.anlegen("K-1001", "Inkasso AG").getId();
        Long erstesKonto = kontoService.anlegen("AZ-1", kreditor, null).getId();
        Long anderesKonto = kontoService.anlegen("AZ-2", kreditor, null).getId();

        for (int runde = 1; runde <= 5; runde++) {
            String clientRequestId = "race-konten-" + runde;
            SollbuchungBefehl befehl = new SollbuchungBefehl(Buchungsart.ZINSEN, new BigDecimal("10.00"),
                    LocalDate.of(2026, 2, 1), clientRequestId);

            List<Ausgang<BuchungsErgebnis>> ausgaenge = parallel(
                    () -> buchungService.sollbuchen(erstesKonto, befehl),
                    () -> buchungService.sollbuchen(anderesKonto, befehl));

            assertThat(ausgaenge).filteredOn(a -> a.fehler() == null)
                    .singleElement()
                    .satisfies(a -> assertThat(a.ergebnis().neuAngelegt()).isTrue());
            assertThat(ausgaenge).filteredOn(a -> a.fehler() != null)
                    .singleElement()
                    .satisfies(a -> assertThat(a.fehler()).isInstanceOf(KonfliktException.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM buchung WHERE client_request_id = ?", Integer.class,
                    clientRequestId)).isEqualTo(1);
        }

        assertThat(anzahlBuchungen()).isEqualTo(5);
        assertThat(kontoService.laden(erstesKonto).getSaldo().add(kontoService.laden(anderesKonto).getSaldo()))
                .isEqualByComparingTo("50.00");
    }
}
