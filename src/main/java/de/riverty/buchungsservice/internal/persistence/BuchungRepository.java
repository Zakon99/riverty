package de.riverty.buchungsservice.internal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;

public interface BuchungRepository extends JpaRepository<Buchung, Long> {

    default Buchung findeVorhanden(Long id) {
        return findById(id).orElseThrow(() -> new NichtGefundenException("Buchung " + id + " nicht gefunden."));
    }

    /**
     * Buchung zur clientRequestId mit allen Beziehungen, die ein Aufrufer am Ergebnis liest; das Replay-Ergebnis ist
     * damit auch außerhalb einer Transaktion vollständig lesbar.
     */
    @EntityGraph(attributePaths = { "konto", "kreditor", "debitor", "stornoVon" })
    Optional<Buchung> findByClientRequestId(String clientRequestId);

    boolean existsByStornoVonId(Long buchungId);

    /** Historie eines Kontos; die stornierte Buchung wird mitgeladen, damit die Saldowirkung ohne N+1 bestimmbar ist. */
    @EntityGraph(attributePaths = "stornoVon")
    List<Buchung> findByKontoIdOrderByValutaAscBuchungszeitAscIdAsc(Long kontoId);

    @Query("""
            select b from Buchung b
            where b.konto.id = :kontoId
              and b.art in :arten
              and not exists (select s from Buchung s where s.stornoVon = b)
            """)
    List<Buchung> findeNichtStornierte(Long kontoId, Collection<Buchungsart> arten);
}
