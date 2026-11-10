package de.riverty.buchungsservice.internal.domain;

import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Schlüssel zur Dublettenerkennung von Debitoren. Die Felder werden einzeln normalisiert, maskiert ('\' → '\',
 * '|' → '\|') und durch '|' getrennt. So bleiben Feldgrenzen eindeutig: "Anna Lena"/"Schmidt" ist nicht
 * "Anna"/"Lena Schmidt", und "Anna|Lena"/"Schmidt" ist nicht "Anna"/"Lena|Schmidt".
 * <p>
 * Länge: Normalisierung (ä → ae usw.) und Maskierung verdoppeln ein Zeichen höchstens. Bei den Spaltenlängen
 * Vorname/Nachname je 100, PLZ 10 und Datum 10 sind das maximal 2·100 + 2·100 + 10 + 2·10 + 3 Trenner = 433 Zeichen,
 * die Spalte fasst 500.
 */
public record Dublettenschluessel(String wert) {

    private static final String TRENNER = "|";

    public static Dublettenschluessel of(String vorname, String nachname, LocalDate geburtsdatum, String plz) {
        return new Dublettenschluessel(Stream.of(vorname, nachname, geburtsdatum.toString(), plz)
                .map(Dublettenschluessel::normalisieren)
                .map(Dublettenschluessel::maskieren)
                .collect(Collectors.joining(TRENNER)));
    }

    /** trim, lowercase, ä→ae, ö→oe, ü→ue, ß→ss, Mehrfach-Leerzeichen → eins. Sonst bleibt alles unverändert. */
    private static String normalisieren(String feld) {
        return feld.strip()
                .toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
                .replaceAll("\s+", " ");
    }

    /** Maskiert zuerst das Maskierungszeichen selbst, dann den Feldtrenner. */
    private static String maskieren(String feld) {
        return feld.replace("\\", "\\\\").replace(TRENNER, "\\" + TRENNER);
    }
}
