package de.riverty.buchungsservice.support;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hilfsfunktionen fuer den direkten DB-Zugriff in Integrationstests.
 */
public final class Datenbank {

    private static final List<String> TABELLEN =
            List.of("verrechnung", "buchung", "konto_debitor", "forderungskonto", "debitor", "kreditor");

    private Datenbank() {
    }

    /** Leert alle Fachtabellen; Selbstreferenzen (stornoVon, aufhebungVon) duerfen das Leeren nicht blockieren. */
    public static void leeren(JdbcTemplate jdbc) {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            TABELLEN.forEach(tabelle -> jdbc.execute("DELETE FROM " + tabelle));
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    public static int anzahl(JdbcTemplate jdbc, String tabelle) {
        Integer anzahl = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabelle, Integer.class);
        return anzahl == null ? 0 : anzahl;
    }
}
