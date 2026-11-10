package de.riverty.buchungsservice.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Liest JSON-Antworten verlustfrei: Dezimalzahlen werden als {@link BigDecimal} mit unveraenderter Scale gelesen,
 * damit Betraege robust (und inklusive Scale 2) geprueft werden koennen.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Json() {
    }

    public static JsonNode lesen(String json) {
        return MAPPER.readTree(json);
    }

    /** Prueft, dass der Knoten eine JSON-Zahl mit Scale 2 ist, und liefert ihren Wert. */
    public static BigDecimal betrag(JsonNode knoten) {
        assertThat(knoten.isNumber()).as("Betrag ist eine JSON-Zahl: %s", knoten).isTrue();
        BigDecimal wert = knoten.decimalValue();
        assertThat(wert.scale()).as("Betrag hat Scale 2: %s", knoten).isEqualTo(2);
        return wert;
    }

    /** Prueft einen Betrag (JSON-Zahl, Scale 2, wertgleich). */
    public static void assertBetrag(JsonNode knoten, String erwartet) {
        assertThat(betrag(knoten)).isEqualByComparingTo(erwartet);
    }

    /** Liefert eine Kopie des JSON-Objekts, in der {@code feld} auf {@code wert} gesetzt ist ({@code null} = JSON-null). */
    public static String mit(String json, String feld, Object wert) {
        ObjectNode objekt = (ObjectNode) lesen(json);
        objekt.set(feld, MAPPER.valueToTree(wert));
        return MAPPER.writeValueAsString(objekt);
    }

    /** Liefert eine Kopie des JSON-Objekts ohne {@code feld}. */
    public static String ohne(String json, String feld) {
        ObjectNode objekt = (ObjectNode) lesen(json);
        objekt.remove(feld);
        return MAPPER.writeValueAsString(objekt);
    }
}
