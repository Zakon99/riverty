package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.DuplikatException;
import de.riverty.buchungsservice.internal.domain.Kreditor;

class BeteiligteServiceTest extends ServiceTestBasis {

    private static final LocalDate GEBURTSDATUM = LocalDate.of(1980, 5, 17);

    @Test
    @DisplayName("S1-AK-04: Kreditor anlegen → gespeichert mit neuer Id")
    void kreditorAnlegen() {
        Kreditor kreditor = kreditorService.anlegen("K-4711", "Muster GmbH");

        assertThat(kreditor.getId()).isNotNull();
        assertThat(kreditor.getKreditorennummer()).isEqualTo("K-4711");
        assertThat(kreditor.getName()).isEqualTo("Muster GmbH");
        assertThat(anzahl("kreditor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S1-AK-04: Kreditorennummer wird getrimmt, Gross-/Kleinschreibung bleibt")
    void kreditorennummerGetrimmtCaseBleibt() {
        Kreditor kreditor = kreditorService.anlegen("  k-4712 ", "Muster GmbH");

        assertThat(kreditor.getKreditorennummer()).isEqualTo("k-4712");
    }

    @Test
    @DisplayName("S1-AK-05: Zweiter Kreditor mit gleicher Nummer → Duplikat mit Id des vorhandenen")
    void kreditorDuplikat() {
        Kreditor vorhanden = kreditorService.anlegen("K-4711", "Muster GmbH");

        assertThatThrownBy(() -> kreditorService.anlegen("K-4711", "Andere GmbH"))
                .isInstanceOfSatisfying(DuplikatException.class,
                        e -> assertThat(e.getVorhandeneId()).isEqualTo(vorhanden.getId()));
        assertThatThrownBy(() -> kreditorService.anlegen(" K-4711 ", "Andere GmbH"))
                .isInstanceOfSatisfying(DuplikatException.class,
                        e -> assertThat(e.getVorhandeneId()).isEqualTo(vorhanden.getId()));
        assertThat(anzahl("kreditor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S1-AK-06: Debitor anlegen → Dublettenschluessel aus normalisierten, getrennten Feldern")
    void debitorAnlegen() {
        Debitor debitor = debitorService.anlegen("  Jörg ", "Müller  Weiß", GEBURTSDATUM, "10115");

        assertThat(debitor.getId()).isNotNull();
        assertThat(debitor.getGeburtsdatum()).isEqualTo(GEBURTSDATUM);
        assertThat(debitor.getDublettenschluessel()).isEqualTo("joerg|mueller weiss|1980-05-17|10115");
        assertThat(anzahl("debitor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S1-AK-07: Debitor mit normalisiert gleichen Daten → Duplikat mit Id aus AK-06")
    void debitorDuplikat() {
        Debitor vorhanden = debitorService.anlegen("  Jörg ", "Müller  Weiß", GEBURTSDATUM, "10115");

        assertThatThrownBy(() -> debitorService.anlegen("JÖRG", "MUELLER WEISS", GEBURTSDATUM, " 10115 "))
                .isInstanceOfSatisfying(DuplikatException.class,
                        e -> assertThat(e.getVorhandeneId()).isEqualTo(vorhanden.getId()));
        assertThat(anzahl("debitor")).isEqualTo(1);
    }

    @Test
    @DisplayName("S1-AK-52: Anna|Lena/Schmidt und Anna/Lena|Schmidt (gleiches Geburtsdatum/PLZ) → zwei Debitoren")
    void feldtrennerImNamenKeineDublette() {
        Debitor a = debitorService.anlegen("Anna|Lena", "Schmidt", GEBURTSDATUM, "10115");
        Debitor b = debitorService.anlegen("Anna", "Lena|Schmidt", GEBURTSDATUM, "10115");

        assertThat(b.getId()).isNotNull().isNotEqualTo(a.getId());
        assertThat(b.getDublettenschluessel()).isNotEqualTo(a.getDublettenschluessel());
        assertThat(anzahl("debitor")).isEqualTo(2);
    }

    @Test
    @DisplayName("S1-AK-58: Parallele Kreditor-Anlage mit gleicher Nummer → genau ein Kreditor, der andere Duplikat mit dessen Id")
    void paralleleKreditorAnlage() throws Exception {
        for (int runde = 1; runde <= 5; runde++) {
            String nummer = "K-RACE-" + runde;
            List<Ausgang<Kreditor>> ausgaenge = parallel(
                    () -> kreditorService.anlegen(nummer, "Erste GmbH"),
                    () -> kreditorService.anlegen(nummer, "Zweite GmbH"));

            List<Kreditor> erfolgreich = ausgaenge.stream()
                    .filter(a -> a.fehler() == null)
                    .map(Ausgang::ergebnis)
                    .toList();
            assertThat(erfolgreich).hasSize(1);
            Kreditor angelegt = erfolgreich.get(0);
            assertThat(angelegt.getId()).isNotNull();
            assertThat(ausgaenge).filteredOn(a -> a.fehler() != null)
                    .singleElement()
                    .satisfies(a -> assertThat(a.fehler()).isInstanceOfSatisfying(DuplikatException.class,
                            e -> assertThat(e.getVorhandeneId()).isEqualTo(angelegt.getId())));
            Integer anzahlMitNummer = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kreditor WHERE kreditorennummer = ?", Integer.class, nummer);
            assertThat(anzahlMitNummer).isEqualTo(1);
        }
        assertThat(anzahl("kreditor")).isEqualTo(5);
    }

    @Test
    @DisplayName("S1-AK-08: Gleicher Name, andere PLZ bzw. anderes Geburtsdatum → neuer Debitor")
    void anderePlzOderGeburtsdatumErgibtNeuenDebitor() {
        Debitor original = debitorService.anlegen("Jörg", "Müller Weiß", GEBURTSDATUM, "10115");

        Debitor anderePlz = debitorService.anlegen("Jörg", "Müller Weiß", GEBURTSDATUM, "10117");
        Debitor anderesDatum = debitorService.anlegen("Jörg", "Müller Weiß", GEBURTSDATUM.plusDays(1), "10115");

        assertThat(anderePlz.getId()).isNotNull().isNotEqualTo(original.getId());
        assertThat(anderesDatum.getId()).isNotNull().isNotEqualTo(original.getId()).isNotEqualTo(anderePlz.getId());
        assertThat(anzahl("debitor")).isEqualTo(3);
    }
}
