package de.riverty.buchungsservice.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

import de.riverty.buchungsservice.internal.domain.AnfrageKennung;
import de.riverty.buchungsservice.internal.domain.Betrag;

/**
 * Bildet die Idempotenzmerkmale einer Anfrage: SHA-256 (hex) über die normalisierten Felder. Beträge gehen gerundet
 * ein (12 und 12.00 ergeben denselben Hash); jedes Feld wird mit seiner Länge kodiert, damit Feldgrenzen eindeutig sind.
 */
final class RequestHash {

    private RequestHash() {
    }

    static AnfrageKennung kennung(String clientRequestId, Object... felder) {
        return new AnfrageKennung(clientRequestId, hash(felder));
    }

    private static String hash(Object... felder) {
        String normalisiert = Arrays.stream(felder).map(RequestHash::kodiere).collect(Collectors.joining("|"));
        return HexFormat.of().formatHex(sha256().digest(normalisiert.getBytes(StandardCharsets.UTF_8)));
    }

    private static String kodiere(Object feld) {
        if (feld == null) {
            return "-";
        }
        String text = feld instanceof Betrag betrag ? betrag.wert().toPlainString() : feld.toString();
        return text.length() + ":" + text;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist in dieser JVM nicht verfügbar.", e);
        }
    }
}
