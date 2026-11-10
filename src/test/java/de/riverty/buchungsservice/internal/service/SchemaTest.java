package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;

class SchemaTest extends ServiceTestBasis {

    private static final List<String> FACHTABELLEN =
            List.of("kreditor", "debitor", "forderungskonto", "konto_debitor", "buchung", "verrechnung");

    @Autowired
    private Environment environment;

    private record Spalte(String tabelle, String name, String typ, int praezision, int nachkommastellen) {
    }

    @Test
    @DisplayName("S1-AK-47: Flyway hat das Schema angelegt, Hibernate validiert (ddl-auto=validate)")
    void flywayUndValidierung() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        Integer erfolgreicheMigrationen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(erfolgreicheMigrationen).isPositive();
        Integer fehlgeschlageneMigrationen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE", Integer.class);
        assertThat(fehlgeschlageneMigrationen).isZero();
        FACHTABELLEN.forEach(tabelle -> assertThat(spalten(tabelle)).as("Tabelle %s", tabelle).isNotEmpty());
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({"forderungskonto, saldo", "buchung, betrag", "verrechnung, betrag"})
    @DisplayName("S1-AK-47: Betragsspalten sind NUMERIC(15,2)")
    void betragsspaltenSindNumeric15Komma2(String tabelle, String spalte) {
        assertThat(spalten(tabelle))
                .filteredOn(s -> s.name().equalsIgnoreCase(spalte))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.typ()).isIn("NUMERIC", "DECIMAL");
                    assertThat(s.praezision()).isEqualTo(15);
                    assertThat(s.nachkommastellen()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("S1-AK-47: Alle Dezimalspalten der Fachtabellen sind NUMERIC(15,2)")
    void alleDezimalspaltenSindNumeric15Komma2() {
        List<Spalte> dezimal = FACHTABELLEN.stream()
                .flatMap(t -> spalten(t).stream())
                .filter(s -> s.typ().equals("NUMERIC") || s.typ().equals("DECIMAL"))
                .toList();

        assertThat(dezimal).isNotEmpty().allSatisfy(s -> {
            assertThat(s.praezision()).as("%s.%s", s.tabelle(), s.name()).isEqualTo(15);
            assertThat(s.nachkommastellen()).as("%s.%s", s.tabelle(), s.name()).isEqualTo(2);
        });
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({
        "kreditor, kreditorennummer",
        "debitor, dublettenschluessel",
        "forderungskonto, aktenzeichen",
        "buchung, client_request_id"
    })
    @DisplayName("S1-AK-47: Unique-Constraint auf genau dieser Spalte")
    void uniqueConstraint(String tabelle, String spalte) {
        assertThat(eindeutigeIndizes(tabelle).values())
                .as("Eindeutige Indizes auf %s", tabelle)
                .anySatisfy(spaltenDesIndex -> assertThat(spaltenDesIndex).containsExactly(spalte));
    }

    private List<Spalte> spalten(String tabelle) {
        List<Spalte> ergebnis = jdbc.execute((ConnectionCallback<List<Spalte>>) verbindung -> {
            DatabaseMetaData metaData = verbindung.getMetaData();
            List<Spalte> liste = new ArrayList<>();
            try (ResultSet rs = metaData.getColumns(null, null, tabelle, null)) {
                while (rs.next()) {
                    liste.add(new Spalte(tabelle, rs.getString("COLUMN_NAME").toLowerCase(),
                            rs.getString("TYPE_NAME").toUpperCase(), rs.getInt("COLUMN_SIZE"),
                            rs.getInt("DECIMAL_DIGITS")));
                }
            }
            return liste;
        });
        return ergebnis == null ? List.of() : ergebnis;
    }

    /** Eindeutige Indizes (inkl. Unique-Constraints) einer Tabelle: Indexname → Spalten in Reihenfolge. */
    private Map<String, List<String>> eindeutigeIndizes(String tabelle) {
        Map<String, List<String>> ergebnis = jdbc.execute((ConnectionCallback<Map<String, List<String>>>) verbindung -> {
            Map<String, List<String>> indizes = new LinkedHashMap<>();
            try (ResultSet rs = verbindung.getMetaData().getIndexInfo(null, null, tabelle, true, false)) {
                while (rs.next()) {
                    String spalte = rs.getString("COLUMN_NAME");
                    if (spalte != null) {
                        indizes.computeIfAbsent(rs.getString("INDEX_NAME"), k -> new ArrayList<>())
                                .add(spalte.toLowerCase());
                    }
                }
            }
            return indizes;
        });
        return ergebnis == null ? Map.of() : ergebnis;
    }
}
