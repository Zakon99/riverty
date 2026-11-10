package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.FachlicheRegelVerletztException;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.KonfliktException;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;
import de.riverty.buchungsservice.internal.domain.StornoGrund;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.domain.VerrechnungsArt;
import de.riverty.buchungsservice.internal.service.Testdaten.Grundkonto;

class StornoTest extends ServiceTestBasis {

    @Test
    @DisplayName("S1-AK-25: Storno einer Zahlung → STORNO mit Valuta des Originals, Aufhebungen, Posten wieder offen")
    void stornoZahlung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", LocalDate.of(2026, 3, 1));
        List<Verrechnung> originale = verrechnungenVon(g.kontoId(), zahlung);
        Momentaufnahme vorher = momentaufnahme();
        clock.vorstellen(Duration.ofDays(3));
        Instant jetzt = clock.instant();

        BuchungsErgebnis ergebnis = buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "storno-25"));

        Buchung storno = ergebnis.buchung();
        assertThat(ergebnis.neuAngelegt()).isTrue();
        assertThat(storno.getArt()).isEqualTo(Buchungsart.STORNO);
        assertThat(storno.getBetrag().wert()).isEqualByComparingTo("12.00");
        assertThat(storno.getValuta()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(storno.getBuchungszeit()).isEqualTo(jetzt);
        assertThat(storno.getStornoVon().getId()).isEqualTo(zahlung.getId());
        assertThat(storno.getGrund()).isEqualTo("RUECKLASTSCHRIFT");
        assertThat(storno.getAusloeser()).isEqualTo("sb-mueller");
        assertThat(storno.getBemerkung()).isNull();

        Map<Long, Verrechnung> originalNachId = originale.stream()
                .collect(Collectors.toMap(Verrechnung::getId, Function.identity()));
        List<Verrechnung> aufhebungen = verrechnungenAusgeloestDurch(g.kontoId(), storno);
        assertThat(aufhebungen)
                .hasSize(2)
                .allSatisfy(a -> {
                    assertThat(a.getArt()).isEqualTo(VerrechnungsArt.AUFHEBUNG);
                    assertThat(a.getZeitpunkt()).isEqualTo(jetzt);
                    Verrechnung original = originalNachId.get(a.getAufhebungVon().getId());
                    assertThat(original).isNotNull();
                    assertThat(a.getBetrag()).isEqualTo(original.getBetrag());
                    assertThat(a.getSollBuchung().getId()).isEqualTo(original.getSollBuchung().getId());
                    assertThat(a.getHabenBuchung().getId()).isEqualTo(zahlung.getId());
                })
                .extracting(a -> a.getBetrag().wert())
                .containsExactlyInAnyOrder(eur("5.00"), eur("7.00"));

        assertThat(offen(g.kontoId(), g.geb())).isEqualByComparingTo("5.00");
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("10.00");
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("100.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertNurNeueDatensaetze(vorher);
        assertThat(buchungService.historie(g.kontoId()))
                .filteredOn(b -> b.getId().equals(zahlung.getId()))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.getArt()).isEqualTo(Buchungsart.ZAHLUNG);
                    assertThat(b.getBetrag().wert()).isEqualByComparingTo("12.00");
                    assertThat(b.getStornoVon()).isNull();
                });
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-26: Storno der Ueberzahlung (AK-18) → Aufhebungen 5, 10, 100; Guthaben 0; Saldo 115.00")
    void stornoUeberzahlung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zustandAk18(g);
        Momentaufnahme vorher = momentaufnahme();

        Buchung storno = testdaten.storno(g.kontoId(), zahlung, StornoGrund.RUECKLASTSCHRIFT);

        assertThat(verrechnungenAusgeloestDurch(g.kontoId(), storno))
                .allSatisfy(a -> assertThat(a.getArt()).isEqualTo(VerrechnungsArt.AUFHEBUNG))
                .extracting(a -> a.getSollBuchung().getId(), a -> a.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(g.geb().getId(), eur("5.00")),
                        tuple(g.zinsen().getId(), eur("10.00")),
                        tuple(g.hf().getId(), eur("100.00")));
        assertThat(guthaben(g.kontoId(), g.d2())).isEqualByComparingTo("0.00");
        assertThat(offen(g.kontoId(), g.geb())).isEqualByComparingTo("5.00");
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("10.00");
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("100.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");
        assertNurNeueDatensaetze(vorher);
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-27: Storno einer teilbezahlten Sollbuchung → Aufhebung, Guthaben beim Zahler, keine Neuverteilung")
    void stornoTeilbezahlteSollbuchung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", LocalDate.of(2026, 3, 1));
        Verrechnung zinsVerrechnung = verrechnungenVon(g.kontoId(), zahlung).stream()
                .filter(v -> v.getSollBuchung().getId().equals(g.zinsen().getId()))
                .findFirst()
                .orElseThrow();
        Momentaufnahme vorher = momentaufnahme();

        BuchungsErgebnis ergebnis = buchungService.stornieren(g.kontoId(), g.zinsen().getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, "falscher Zinssatz", "sb-mueller", "storno-27"));

        Buchung storno = ergebnis.buchung();
        assertThat(storno.getArt()).isEqualTo(Buchungsart.STORNO);
        assertThat(storno.getBetrag().wert()).isEqualByComparingTo("10.00");
        assertThat(storno.getValuta()).isEqualTo(Testdaten.VALUTA_ZINSEN);
        assertThat(storno.getStornoVon().getId()).isEqualTo(g.zinsen().getId());
        assertThat(storno.getGrund()).isEqualTo("FEHLBUCHUNG");
        assertThat(storno.getBemerkung()).isEqualTo("falscher Zinssatz");
        assertThat(verrechnungenAusgeloestDurch(g.kontoId(), storno)).singleElement().satisfies(a -> {
            assertThat(a.getArt()).isEqualTo(VerrechnungsArt.AUFHEBUNG);
            assertThat(a.getBetrag().wert()).isEqualByComparingTo("7.00");
            assertThat(a.getAufhebungVon().getId()).isEqualTo(zinsVerrechnung.getId());
        });
        assertThat(offenePostenIds(g.kontoId())).containsExactly(g.hf().getId());
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("100.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("7.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("93.00");
        assertNurNeueDatensaetze(vorher);
        assertKonsistent(g.kontoId());

        List<Verrechnung> neu = verrechnungService.verrechnen(g.kontoId());

        assertThat(neu).singleElement().satisfies(v -> {
            assertThat(v.getSollBuchung().getId()).isEqualTo(g.hf().getId());
            assertThat(v.getHabenBuchung().getId()).isEqualTo(zahlung.getId());
            assertThat(v.getBetrag().wert()).isEqualByComparingTo("7.00");
        });
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("93.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("93.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-27: Storno einer von mehreren Debitoren bezahlten Sollbuchung → Guthaben je Zahler")
    void stornoMitMehrerenZahlern() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Debitor d2 = testdaten.debitor("Dieter", "Zwei");
        Forderungskonto konto = testdaten.konto("AZ-1", kr1, d1);
        testdaten.zuordnen(konto, d2);
        Buchung zinsen = testdaten.soll(konto.getId(), Buchungsart.ZINSEN, "10.00", Testdaten.VALUTA_ZINSEN);
        testdaten.zahlung(konto.getId(), d1, "4.00", Testdaten.VALUTA_ZAHLUNG);
        testdaten.zahlung(konto.getId(), d2, "6.00", Testdaten.VALUTA_ZAHLUNG);

        testdaten.storno(konto.getId(), zinsen, StornoGrund.FEHLBUCHUNG);

        assertThat(guthaben(konto.getId(), d1)).isEqualByComparingTo("4.00");
        assertThat(guthaben(konto.getId(), d2)).isEqualByComparingTo("6.00");
        assertThat(saldo(konto.getId())).isEqualByComparingTo("-10.00");
        assertThat(verrechnungService.offenePosten(konto.getId())).isEmpty();
        assertKonsistent(konto.getId());
    }

    @Test
    @DisplayName("S1-AK-28: Storno einer unbezahlten Sollbuchung → Saldo 0.00, nichts offen, keine Aufhebung")
    void stornoUnbezahlteSollbuchung() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Long konto = testdaten.konto("AZ-1", kr1, null).getId();
        Buchung hf = testdaten.soll(konto, Buchungsart.HAUPTFORDERUNG, "100.00", Testdaten.VALUTA_HF);

        Buchung storno = testdaten.storno(konto, hf, StornoGrund.FEHLBUCHUNG);

        assertThat(storno.getValuta()).isEqualTo(Testdaten.VALUTA_HF);
        assertThat(saldo(konto)).isEqualByComparingTo("0.00");
        assertThat(verrechnungService.offenePosten(konto)).isEmpty();
        assertThat(verrechnungService.verrechnungen(konto)).isEmpty();
        assertKonsistent(konto);
    }

    @Test
    @DisplayName("S1-AK-29: Bereits stornierte Buchung erneut stornieren (andere clientRequestId) → Konflikt")
    void doppelstorno() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        testdaten.storno(g.kontoId(), zahlung, StornoGrund.RUECKLASTSCHRIFT);
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");

        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "storno-nochmal")))
                .isInstanceOf(KonfliktException.class);
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");
    }

    @Test
    @DisplayName("S1-AK-30: Storno einer Storno-Buchung → Konflikt")
    void stornoEinesStornos() {
        Grundkonto g = testdaten.grundkonto();
        Buchung storno = testdaten.storno(g.kontoId(), g.geb(), StornoGrund.FEHLBUCHUNG);
        int buchungen = anzahl("buchung");

        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), storno.getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "storno-vom-storno")))
                .isInstanceOf(KonfliktException.class);
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("110.00");
    }

    /** Zustand AK-33: Grundkonto, D1 zahlte 12.00, dann Ausbuchung 103.00 (ZINSEN 3, HF 100). */
    private Buchung zustandAk33(Grundkonto g) {
        testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        return testdaten.ausbuchung(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-31: Storno einer Sollbuchung mit verrechneter Ausbuchung → FachlicheRegelVerletzt")
    void stornoSollbuchungMitAusbuchungVerboten() {
        Grundkonto g = testdaten.grundkonto();
        zustandAk33(g);
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");

        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), g.hf().getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "storno-hf")))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), g.zinsen().getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "storno-zinsen")))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("S1-AK-31: Storno der nur durch Zahlung verrechneten GEBUEHREN nach Ausbuchung erlaubt → Guthaben 5.00")
    void stornoGebuehrenNachAusbuchungErlaubt() {
        Grundkonto g = testdaten.grundkonto();
        zustandAk33(g);
        Momentaufnahme vorher = momentaufnahme();

        testdaten.storno(g.kontoId(), g.geb(), StornoGrund.FEHLBUCHUNG);

        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("5.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("-5.00");
        assertThat(verrechnungService.offenePosten(g.kontoId())).isEmpty();
        assertNurNeueDatensaetze(vorher);
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-31: Nach Storno der Ausbuchung ist Storno der HAUPTFORDERUNG erlaubt")
    void stornoHauptforderungNachStornoDerAusbuchung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung ausbuchung = zustandAk33(g);
        testdaten.storno(g.kontoId(), ausbuchung, StornoGrund.SONSTIGES);

        Buchung storno = testdaten.storno(g.kontoId(), g.hf(), StornoGrund.FEHLBUCHUNG);

        assertThat(storno.getStornoVon().getId()).isEqualTo(g.hf().getId());
        assertThat(offenePostenIds(g.kontoId())).containsExactly(g.zinsen().getId());
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("3.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("3.00");
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-54: Storno mit grund null bzw. ausloeser null/blank → FachlicheRegelVerletzt, nichts persistiert")
    void stornoOhneGrundOderAusloeser() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Momentaufnahme vorher = momentaufnahme();
        int buchungen = anzahl("buchung");
        int verrechnungen = anzahl("verrechnung");

        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(null, null, "sb-mueller", "storno-54-grund")))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, null, "storno-54-null")))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "   ", "storno-54-blank")))
                .isInstanceOf(FachlicheRegelVerletztException.class);

        assertThat(anzahl("buchung")).isEqualTo(buchungen);
        assertThat(anzahl("verrechnung")).isEqualTo(verrechnungen);
        assertThat(momentaufnahme()).isEqualTo(vorher);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("103.00");
        assertKonsistent(g.kontoId());

        Buchung storno = buchungService.stornieren(g.kontoId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "storno-54-grund")).buchung();
        assertThat(storno.getStornoVon().getId())
                .as("abgelehnte clientRequestId ist nicht verbraucht")
                .isEqualTo(zahlung.getId());
    }

    @Test
    @DisplayName("S1-AK-55: Storno einer Zahlung mit Verrechnungen teils aus expliziter Verrechnung → alle aufgehoben")
    void stornoZahlungMitExpliziterVerrechnung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "130.00", Testdaten.VALUTA_ZAHLUNG);
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("15.00");
        Buchung zinsen2 = testdaten.soll(g.kontoId(), Buchungsart.ZINSEN, "20.00", LocalDate.of(2026, 4, 1));
        List<Verrechnung> explizit = verrechnungService.verrechnen(g.kontoId());
        assertThat(explizit).singleElement().satisfies(v -> {
            assertThat(v.getSollBuchung().getId()).isEqualTo(zinsen2.getId());
            assertThat(v.getHabenBuchung().getId()).isEqualTo(zahlung.getId());
            assertThat(v.getBetrag().wert()).isEqualByComparingTo("15.00");
        });
        clock.vorstellen(Testdaten.SCHRITT);
        List<Verrechnung> aktiveVorher = verrechnungenVon(g.kontoId(), zahlung);
        assertThat(aktiveVorher).hasSize(4);
        Momentaufnahme vorher = momentaufnahme();

        Buchung storno = testdaten.storno(g.kontoId(), zahlung, StornoGrund.RUECKLASTSCHRIFT);

        assertThat(verrechnungenAusgeloestDurch(g.kontoId(), storno))
                .allSatisfy(a -> {
                    assertThat(a.getArt()).isEqualTo(VerrechnungsArt.AUFHEBUNG);
                    assertThat(a.getHabenBuchung().getId()).isEqualTo(zahlung.getId());
                })
                .extracting(a -> a.getAufhebungVon().getId())
                .containsExactlyInAnyOrderElementsOf(aktiveVorher.stream().map(Verrechnung::getId).toList());
        assertThat(offen(g.kontoId(), g.geb())).isEqualByComparingTo("5.00");
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("10.00");
        assertThat(offen(g.kontoId(), zinsen2)).isEqualByComparingTo("20.00");
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("100.00");
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("135.00");
        assertNurNeueDatensaetze(vorher);
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-55: Storno einer Zahlung nach Ausbuchung → Zahlungs-Verrechnungen aufgehoben, Posten offen, konsistent")
    void stornoZahlungNachAusbuchung() {
        Grundkonto g = testdaten.grundkonto();
        Buchung zahlung = testdaten.zahlung(g.kontoId(), g.d1(), "12.00", Testdaten.VALUTA_ZAHLUNG);
        Buchung ausbuchung = testdaten.ausbuchung(g.kontoId());
        List<Verrechnung> ausbuchungsVerrechnungen = verrechnungenVon(g.kontoId(), ausbuchung);
        Momentaufnahme vorher = momentaufnahme();

        Buchung storno = testdaten.storno(g.kontoId(), zahlung, StornoGrund.RUECKLASTSCHRIFT);

        assertThat(verrechnungenAusgeloestDurch(g.kontoId(), storno))
                .allSatisfy(a -> {
                    assertThat(a.getArt()).isEqualTo(VerrechnungsArt.AUFHEBUNG);
                    assertThat(a.getHabenBuchung().getId()).isEqualTo(zahlung.getId());
                })
                .extracting(a -> a.getSollBuchung().getId(), a -> a.getBetrag().wert())
                .containsExactlyInAnyOrder(
                        tuple(g.geb().getId(), eur("5.00")),
                        tuple(g.zinsen().getId(), eur("7.00")));
        assertThat(verrechnungenVon(g.kontoId(), ausbuchung))
                .as("Verrechnungen der Ausbuchung bleiben unberuehrt")
                .containsExactlyInAnyOrderElementsOf(ausbuchungsVerrechnungen)
                .allSatisfy(v -> assertThat(v.getArt()).isEqualTo(VerrechnungsArt.VERRECHNUNG));
        assertThat(offen(g.kontoId(), g.geb())).isEqualByComparingTo("5.00");
        assertThat(offen(g.kontoId(), g.zinsen())).isEqualByComparingTo("7.00");
        assertThat(offen(g.kontoId(), g.hf())).isEqualByComparingTo("0.00");
        assertThat(offenePostenIds(g.kontoId())).containsExactly(g.geb().getId(), g.zinsen().getId());
        assertThat(guthaben(g.kontoId(), g.d1())).isEqualByComparingTo("0.00");
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("12.00");
        assertNurNeueDatensaetze(vorher);
        assertKonsistent(g.kontoId());
    }

    @Test
    @DisplayName("S1-AK-32: Storno einer unbekannten Buchung → NichtGefunden")
    void stornoUnbekannteBuchung() {
        Grundkonto g = testdaten.grundkonto();

        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), UNBEKANNT,
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "storno-unbekannt")))
                .isInstanceOf(NichtGefundenException.class);
        assertThat(anzahl("buchung")).isEqualTo(3);
    }

    @Test
    @DisplayName("S1-AK-32: Storno mit Buchungs-Id eines anderen Kontos → FachlicheRegelVerletzt")
    void stornoBuchungAusAnderemKonto() {
        Grundkonto g = testdaten.grundkonto();
        Long az2 = testdaten.konto("AZ-2", g.kreditor(), null).getId();
        Buchung fremd = testdaten.soll(az2, Buchungsart.ZINSEN, "10.00", Testdaten.VALUTA_ZINSEN);

        assertThatThrownBy(() -> buchungService.stornieren(g.kontoId(), fremd.getId(),
                new StornoBefehl(StornoGrund.FEHLBUCHUNG, null, "sb-mueller", "storno-fremd")))
                .isInstanceOf(FachlicheRegelVerletztException.class);
        assertThat(anzahl("buchung")).isEqualTo(4);
        assertThat(saldo(g.kontoId())).isEqualByComparingTo("115.00");
        assertThat(saldo(az2)).isEqualByComparingTo("10.00");
    }
}
