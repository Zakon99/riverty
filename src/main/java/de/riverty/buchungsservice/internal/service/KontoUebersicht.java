package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.util.List;

import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.Kreditor;

/**
 * Lesemodell eines Kontos (Suche, Kontoanlage). Der Kreditor ist geladen, die Debitoren nur als Ids enthalten.
 * <p>
 * Außerhalb der Service-Transaktion ({@code open-in-view: false}) dürfen von Relationen des Kreditors nur Ids gelesen
 * werden (Lazy-Proxies).
 */
public record KontoUebersicht(Long id, String aktenzeichen, BigDecimal saldo, Kreditor kreditor, List<Long> debitorIds) {

    /** Erwartet ein Konto, dessen Kreditor und Zuordnungen bereits geladen sind. */
    static KontoUebersicht von(Forderungskonto konto) {
        List<Long> debitorIds = konto.getDebitoren().stream().map(zuordnung -> zuordnung.getDebitor().getId()).toList();
        return new KontoUebersicht(konto.getId(), konto.getAktenzeichen(), konto.getSaldo(), konto.getKreditor(),
                debitorIds);
    }
}
