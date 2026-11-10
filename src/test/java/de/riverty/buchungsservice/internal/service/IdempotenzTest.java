package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.AusbuchungsGrund;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.KonfliktException;
import de.riverty.buchungsservice.internal.domain.StornoGrund;
import de.riverty.buchungsservice.internal.service.Testdaten.Grundkonto;

class IdempotenzTest extends ServiceTestBasis {

    private static final LocalDate VALUTA = LocalDate.of(2026, 3, 1);

    private BuchungsErgebnis zahle(Long kontoId, Debitor debitor, String betrag, String clientRequestId) {
        return buchungService.zahlen(kontoId, new ZahlungBefehl(debitor.getId(), eur(betrag), VALUTA, clientRequestId));
    }

    @Test
    @DisplayName("S1-AK-42: Neue Zahlung mit clientRequestId → neu angelegt, clientRequestId und requestHash gespeichert")
    void neueZahlungSpeichertIdempotenzmerkmale() {
        Grundkonto g = testdaten.grundkonto();

        BuchungsErgebnis ergebnis = zahle(g.kontoId(), g.d1(), "12.00", "zahlung-001");

        assertThat(ergebnis.neuAngelegt()).isTrue();
        assertThat(ergebnis.buchung().getId()).isNotNull();
        assertThat(ergebnis.buchung().getClientRequestId()).isEqualTo("zahlung-001");
        assertThat(ergebnis.buchung().getRequestHash()).isNotBlank();
        assertThat(buchungService.historie(g.kontoId()))
                .filteredOn(b -> b.getId().equals(ergebnis.buchung().getId()))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.getClientRequestId()).isEqualTo("zahlung-001");
                    assertThat(b.getRequestHash()).isEqualTo(ergebnis.buchung().getRequestHash());
                });
    }

    @Test
    @DisplayName("S1-AK-43: Identische Zahlung erneut (auch 12 statt 12.00) → Replay mit derselben Buchung, nichts doppelt")
    void replayZahlung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung original = zahle(g.kontoId(), g.d1(), "12.00", "zahlung-001").buchung();
        clock.vorstellen(Testdaten.SCHRITT);

        BuchungsErgebnis replay = zahle(g.kontoId(), g.d1(), "12.00", "zahlung-001");
        BuchungsErgebnis replayOhneNachkommastellen = zahle(g.kontoId(), g.d1(), "12", "zahlung-001");

        assertThat(replay.neuAngelegt()).isFalse();
        assertThat(replay.buchung().getId()).isEqualTo(original.getId());
        assertThat(replayOhneNachkommastellen.neuAngelegt()).isFalse();
        assertThat(replayOhneNachkommastellen.buchung().getId()).isEqualTo(original.getId());
        assertThat(buchungService.historie(g.kontoId()))
                .filteredOn(b -> b.getArt() == Buchungsart.ZAHLUNG)
                .hasSize(1);
        assertThat(anzahl("buchung")).isEqualTo(4);
        assertThat(verrechnungService.verrechnungen(g.kontoId())).hasSize(2);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("103.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-43: Identische Sollbuchung erneut → Replay")
    void replaySollbuchung() {
        Grundkonto g = testdaten.grundkonto();
        SollbuchungBefehl befehl =
                new SollbuchungBefehl(Buchungsart.ZINSEN, eur("20.00"), LocalDate.of(2026, 4, 1), "soll-001");
        Buchung original = buchungService.sollbuchen(g.kontoId(), befehl).buchung();
        clock.vorstellen(Testdaten.SCHRITT);

        BuchungsErgebnis replay = buchungService.sollbuchen(g.kontoId(),
                new SollbuchungBefehl(Buchungsart.ZINSEN, eur("20"), LocalDate.of(2026, 4, 1), "soll-001"));

        assertThat(replay.neuAngelegt()).isFalse();
        assertThat(replay.buchung().getId()).isEqualTo(original.getId());
        assertThat(anzahl("buchung")).isEqualTo(4);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("135.00");
    }

    @Test
    @DisplayName("S1-AK-43/45a: Identischer Storno erneut → Replay statt Doppelstorno-Konflikt")
    void replayStorno() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", VALUTA);
        StornoBefehl befehl = new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "storno-001");
        Buchung original = buchungService.stornieren(g.kontoId(), zahlung.getId(), befehl).buchung();
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");
        clock.vorstellen(Testdaten.SCHRITT);

        BuchungsErgebnis replay = buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "storno-001"));

        assertThat(replay.neuAngelegt()).isFalse();
        assertThat(replay.buchung().getId()).isEqualTo(original.getId());
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");
    }

    @Test
    @DisplayName("S1-AK-43/45b: Identische Ausbuchung erneut → Replay statt 'nichts offen'")
    void replayAusbuchung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung original = buchungService.ausbuchen(g.kontoId(),
                new AusbuchungBefehl(AusbuchungsGrund.UNEINBRINGLICH, null, "sb-mueller", "ausbuchung-001")).buchung();
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");
        clock.vorstellen(Testdaten.SCHRITT);

        BuchungsErgebnis replay = buchungService.ausbuchen(g.kontoId(),
                new AusbuchungBefehl(AusbuchungsGrund.UNEINBRINGLICH, null, "sb-mueller", "ausbuchung-001"));

        assertThat(replay.neuAngelegt()).isFalse();
        assertThat(replay.buchung().getId()).isEqualTo(original.getId());
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S1-AK-44: Gleiche clientRequestId mit anderem Betrag / als Sollbuchung / auf anderem Konto → Konflikt")
    void gleicheIdAndererInhalt() {
        Grundkonto g = testdaten.grundkonto();
        Long az2 = testdaten.konto("AZ-2", g.kreditor(), g.d1()).getId();
        zahle(g.kontoId(), g.d1(), "12.00", "zahlung-001");
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");

        assertThatThrownBy(() -> zahle(g.kontoId(), g.d1(), "13.00", "zahlung-001"))
                .isInstanceOf(KonfliktException.class);
        assertThatThrownBy(() -> buchungService.sollbuchen(g.kontoId(),
                new SollbuchungBefehl(Buchungsart.ZINSEN, eur("12.00"), VALUTA, "zahlung-001")))
                .isInstanceOf(KonfliktException.class);
        assertThatThrownBy(() -> zahle(az2, g.d1(), "12.00", "zahlung-001"))
                .isInstanceOf(KonfliktException.class);

        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("103.00");
        assertThat(saldo(az2)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S1-AK-44: Gleiche clientRequestId fuer Storno einer anderen Buchung → Konflikt")
    void gleicheStornoIdAndereBuchung() {
        Grundkonto g = testdaten.grundkonto();
        buchungService.stornieren(g.kontoId(), g.geb().getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "storno-001"));
        int buchungen = anzahl("buchung");

        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), g.zinsen().getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "storno-001")))
                .isInstanceOf(KonfliktException.class);
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("S1-AK-46: Parallele identische Requests → genau eine Buchung, der andere ist Replay, kein technischer Fehler")
    void paralleleIdentischeRequests() throws Exception {
        Grundkonto g = testdaten.grundkonto();

        for (int runde = 1; runde <= 5; runde++) {
            String clientRequestId = "race-gleich-" + runde;
            List<Ausgang<BuchungsErgebnis>> ausgaenge = parallel(
                    () -> zahle(g.kontoId(), g.d1(), "1.00", clientRequestId),
                    () -> zahle(g.kontoId(), g.d1(), "1.00", clientRequestId));

            assertThat(ausgaenge).allSatisfy(a -> assertThat(a.fehler()).isNull());
            assertThat(ausgaenge).filteredOn(a -> a.ergebnis().neuAngelegt()).hasSize(1);
            assertThat(ausgaenge).extracting(a -> a.ergebnis().buchung().getId()).containsOnly(
                    ausgaenge.get(0).ergebnis().buchung().getId());
        }

        assertThat(buchungService.historie(g.kontoId()))
                .filteredOn(b -> b.getArt() == Buchungsart.ZAHLUNG)
                .hasSize(5);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("110.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-46: Parallele Requests mit gleicher Id, anderem Inhalt → eine Buchung, der andere Konflikt")
    void paralleleRequestsMitAnderemHash() throws Exception {
        Grundkonto g = testdaten.grundkonto();

        for (int runde = 1; runde <= 5; runde++) {
            String clientRequestId = "race-anders-" + runde;
            List<Ausgang<BuchungsErgebnis>> ausgaenge = parallel(
                    () -> zahle(g.kontoId(), g.d1(), "1.00", clientRequestId),
                    () -> zahle(g.kontoId(), g.d1(), "2.00", clientRequestId));

            assertThat(ausgaenge).filteredOn(a -> a.fehler() == null)
                    .singleElement()
                    .satisfies(a -> assertThat(a.ergebnis().neuAngelegt()).isTrue());
            assertThat(ausgaenge).filteredOn(a -> a.fehler() != null)
                    .singleElement()
                    .satisfies(a -> assertThat(a.fehler()).isInstanceOf(KonfliktException.class));
        }

        assertThat(buchungService.historie(g.kontoId()))
                .filteredOn(b -> b.getArt() == Buchungsart.ZAHLUNG)
                .hasSize(5);
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-55: Gleiche clientRequestId erst als Storno, dann als Ausbuchung → Konflikt, nichts gebucht")
    void gleicheIdStornoDannAusbuchung() {
        Grundkonto g = testdaten.grundkonto();
        buchungService.stornieren(g.kontoId(), g.geb().getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "vorgang-001"));
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");
        clock.vorstellen(Testdaten.SCHRITT);

        assertThatThrownBy(() -> buchungService.ausbuchen(g.kontoId(),
                new AusbuchungBefehl(AusbuchungsGrund.UNEINBRINGLICH, null, "sb-mueller", "vorgang-001")))
                .isInstanceOf(KonfliktException.class);
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("110.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-56: Storno-Replay ausserhalb einer Transaktion vollstaendig lesbar (stornoVon), keine Lazy-Fehler")
    void stornoReplayAusserhalbTransaktionLesbar() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", VALUTA);
        buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "storno-56"));
        clock.vorstellen(Testdaten.SCHRITT);

        BuchungsErgebnis replay = buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "storno-56"));

        assertThat(replay.neuAngelegt()).isFalse();
        Buchung storno = replay.buchung();
        assertThat(storno.getStornoVon().getId()).isEqualTo(zahlung.getId());
        assertThat(storno.getStornoVon().getArt()).isEqualTo(Buchungsart.ZAHLUNG);
        assertThat(storno.getStornoVon().getBetrag().wert()).isEqualByComparingTo("12.00");
        assertThat(storno.getStornoVon().getValuta()).isEqualTo(VALUTA);
    }

    @Test
    @DisplayName("S1-AK-56: Zahlungs-Replay ausserhalb einer Transaktion vollstaendig lesbar (Debitor), keine Lazy-Fehler")
    void zahlungsReplayAusserhalbTransaktionLesbar() {
        Grundkonto g = testdaten.grundkonto();
        zahle(g.kontoId(), g.d1(), "12.00", "zahlung-56");
        clock.vorstellen(Testdaten.SCHRITT);

        BuchungsErgebnis replay = zahle(g.kontoId(), g.d1(), "12.00", "zahlung-56");

        assertThat(replay.neuAngelegt()).isFalse();
        assertThat(replay.buchung().getDebitor().getId()).isEqualTo(g.d1().getId());
        assertThat(replay.buchung().getDebitor().getNachname()).isEqualTo("Eins");
    }

    @Test
    @DisplayName("S1-AK-56: Sollbuchungs-Replay ausserhalb einer Transaktion vollstaendig lesbar (Kreditor), keine Lazy-Fehler")
    void sollbuchungsReplayAusserhalbTransaktionLesbar() {
        Grundkonto g = testdaten.grundkonto();
        SollbuchungBefehl befehl =
                new SollbuchungBefehl(Buchungsart.ZINSEN, eur("20.00"), LocalDate.of(2026, 4, 1), "soll-56");
        buchungService.sollbuchen(g.kontoId(), befehl);
        clock.vorstellen(Testdaten.SCHRITT);

        BuchungsErgebnis replay = buchungService.sollbuchen(g.kontoId(), befehl);

        assertThat(replay.neuAngelegt()).isFalse();
        assertThat(replay.buchung().getKreditor().getId()).isEqualTo(g.kreditor().getId());
        assertThat(replay.buchung().getKreditor().getName()).isEqualTo("Kreditor KR-1");
    }
}
