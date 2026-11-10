package de.riverty.buchungsservice.internal.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;
import jakarta.persistence.LockModeType;

public interface ForderungskontoRepository extends JpaRepository<Forderungskonto, Long> {

    default Forderungskonto findeVorhanden(Long id) {
        return findById(id).orElseThrow(() -> nichtGefunden(id));
    }

    /**
     * Lädt das Konto mit Zeilensperre (SELECT ... FOR UPDATE) für eine Änderung. Parallele Änderungen desselben Kontos
     * warten bis zum Ende der sperrenden Transaktion und lesen danach den aktuellen Stand; so werden alle Buchungen
     * und Verrechnungen eines Kontos serialisiert.
     */
    default Forderungskonto findeVorhandenGesperrt(Long id) {
        return findGesperrtById(id).orElseThrow(() -> nichtGefunden(id));
    }

    /**
     * Lädt das Konto mit Kreditor, Zuordnungen und deren Debitoren in einer Abfrage, damit die Kontoansicht ohne
     * weitere Abfragen (kein N+1) gebildet werden kann.
     */
    default Forderungskonto findeVorhandenMitZuordnungen(Long id) {
        return findMitZuordnungenById(id).orElseThrow(() -> nichtGefunden(id));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from Forderungskonto k where k.id = :id")
    Optional<Forderungskonto> findGesperrtById(Long id);

    @EntityGraph(attributePaths = { "kreditor", "debitoren", "debitoren.debitor" })
    Optional<Forderungskonto> findMitZuordnungenById(Long id);

    Optional<Forderungskonto> findByAktenzeichen(String aktenzeichen);

    /**
     * Konten nach Aktenzeichen und/oder Kreditorennummer (exakter Treffer, UND-verknüpft), sortiert nach Id. Ein
     * Parameter {@code null} schränkt nicht ein. Kreditor und Zuordnungen werden mitgeladen.
     */
    @EntityGraph(attributePaths = { "kreditor", "debitoren" })
    @Query("""
            select k from Forderungskonto k
            where (:aktenzeichen is null or k.aktenzeichen = :aktenzeichen)
              and (:kreditorennummer is null or k.kreditor.kreditorennummer = :kreditorennummer)
            order by k.id
            """)
    List<Forderungskonto> suche(String aktenzeichen, String kreditorennummer);

    private static NichtGefundenException nichtGefunden(Long id) {
        return new NichtGefundenException("Forderungskonto " + id + " nicht gefunden.");
    }
}
