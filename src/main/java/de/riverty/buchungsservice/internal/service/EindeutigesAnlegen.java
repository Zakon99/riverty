package de.riverty.buchungsservice.internal.service;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import de.riverty.buchungsservice.internal.domain.DuplikatException;

/**
 * Legt einen Datensatz mit eindeutigem Merkmal an. Ein vorhandenes Duplikat wird vorab erkannt; legt eine parallele
 * Anfrage es zwischen Prüfung und Commit an, greift der Unique-Constraint, und auch dann gibt es eine
 * {@link DuplikatException} mit der Id des Vorhandenen. Die Nachprüfung läuft bewusst außerhalb der gescheiterten
 * Transaktion.
 */
@Component
class EindeutigesAnlegen {

    private final TransactionTemplate transaktion;

    EindeutigesAnlegen(TransactionTemplate transaktion) {
        this.transaktion = transaktion;
    }

    <T> T anlegen(Supplier<Optional<Long>> vorhandeneId, Supplier<T> anlage, String duplikatMeldung) {
        vorhandeneId.get().ifPresent(id -> {
            throw new DuplikatException(duplikatMeldung, id);
        });
        try {
            return transaktion.execute(status -> anlage.get());
        } catch (DataIntegrityViolationException e) {
            Long id = vorhandeneId.get().orElseThrow(() -> e);
            throw new DuplikatException(duplikatMeldung, id);
        }
    }
}
