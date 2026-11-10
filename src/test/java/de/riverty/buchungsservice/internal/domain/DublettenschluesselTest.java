package de.riverty.buchungsservice.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DublettenschluesselTest {

    private static final LocalDate GEBURTSDATUM = LocalDate.of(1980, 5, 17);

    @Test
    @DisplayName("S1-AK-06: Schluessel aus normalisierten, getrennten Feldern")
    void beispielAusVertrag() {
        Dublettenschluessel schluessel = Dublettenschluessel.of("  Jörg ", "Müller  Weiß", GEBURTSDATUM, "10115");

        assertThat(schluessel.wert()).isEqualTo("joerg|mueller weiss|1980-05-17|10115");
    }

    @Test
    @DisplayName("S1-AK-07: JÖRG / MUELLER WEISS / ' 10115 ' ergibt denselben Schluessel wie AK-06")
    void grossschreibungUndLeerzeichenErgebenGleichenSchluessel() {
        Dublettenschluessel original = Dublettenschluessel.of("  Jörg ", "Müller  Weiß", GEBURTSDATUM, "10115");
        Dublettenschluessel variante = Dublettenschluessel.of("JÖRG", "MUELLER WEISS", GEBURTSDATUM, " 10115 ");

        assertThat(variante).isEqualTo(original);
        assertThat(variante.wert()).isEqualTo(original.wert());
    }

    @ParameterizedTest(name = "''{0}'' -> ''{1}''")
    @CsvSource(delimiter = ';', quoteCharacter = '"', value = {
        "\"  anna  \";anna",
        "ANNA;anna",
        "Ä;ae",
        "Ö;oe",
        "Ü;ue",
        "ä;ae",
        "ö;oe",
        "ü;ue",
        "ß;ss",
        "Straße;strasse",
        "\"anna   lena\";anna lena",
        "\"  Anna    Lena  \";anna lena",
        "René;rené",
        "Hans-Peter;hans-peter",
        "ÄÖÜäöüß;aeoeueaeoeuess"
    })
    @DisplayName("S1-AK-07: Normalisierung einzeln (trim, lowercase, Umlaute, ß, Mehrfach-Leerzeichen; é und Bindestrich bleiben)")
    void normalisiertEinzelnesFeld(String roh, String erwartet) {
        Dublettenschluessel schluessel = Dublettenschluessel.of(roh, "x", GEBURTSDATUM, "1");

        assertThat(schluessel.wert()).isEqualTo(erwartet + "|x|1980-05-17|1");
    }

    @Test
    @DisplayName("S1-AK-07: Normalisierung wirkt auf jedes Feld (Nachname, PLZ)")
    void normalisiertAlleFelder() {
        Dublettenschluessel schluessel = Dublettenschluessel.of("a", "  ÖZDEMIR   GROß ", GEBURTSDATUM, "  D-10115 ");

        assertThat(schluessel.wert()).isEqualTo("a|oezdemir gross|1980-05-17|d-10115");
    }

    @Test
    @DisplayName("S1-AK-07: Feldgrenzen bleiben erhalten (Anna Lena/Schmidt != Anna/Lena Schmidt)")
    void feldgrenzenBleibenErhalten() {
        Dublettenschluessel a = Dublettenschluessel.of("Anna Lena", "Schmidt", GEBURTSDATUM, "10115");
        Dublettenschluessel b = Dublettenschluessel.of("Anna", "Lena Schmidt", GEBURTSDATUM, "10115");

        assertThat(a).isNotEqualTo(b);
        assertThat(a.wert()).isEqualTo("anna lena|schmidt|1980-05-17|10115");
        assertThat(b.wert()).isEqualTo("anna|lena schmidt|1980-05-17|10115");
    }

    @Test
    @DisplayName("S1-AK-52: Feldtrenner '|' im Namen wird maskiert (Anna|Lena/Schmidt != Anna/Lena|Schmidt)")
    void feldtrennerImNamenWirdMaskiert() {
        Dublettenschluessel a = Dublettenschluessel.of("Anna|Lena", "Schmidt", GEBURTSDATUM, "10115");
        Dublettenschluessel b = Dublettenschluessel.of("Anna", "Lena|Schmidt", GEBURTSDATUM, "10115");

        assertThat(a).isNotEqualTo(b);
        assertThat(a.wert()).isNotEqualTo(b.wert());
    }

    @Test
    @DisplayName("S1-AK-52: Maskierung ist deterministisch (gleiche Eingabe mit '|' → gleicher Schluessel)")
    void maskierungIstDeterministisch() {
        Dublettenschluessel a = Dublettenschluessel.of("Anna|Lena", "Schmidt", GEBURTSDATUM, "10115");
        Dublettenschluessel b = Dublettenschluessel.of(" ANNA|LENA ", "schmidt", GEBURTSDATUM, "10115");

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("S1-AK-08: Andere PLZ oder anderes Geburtsdatum ergibt anderen Schluessel")
    void anderePlzOderGeburtsdatum() {
        Dublettenschluessel basis = Dublettenschluessel.of("Jörg", "Müller Weiß", GEBURTSDATUM, "10115");

        assertThat(Dublettenschluessel.of("Jörg", "Müller Weiß", GEBURTSDATUM, "10117")).isNotEqualTo(basis);
        assertThat(Dublettenschluessel.of("Jörg", "Müller Weiß", GEBURTSDATUM.plusDays(1), "10115")).isNotEqualTo(basis);
    }
}
