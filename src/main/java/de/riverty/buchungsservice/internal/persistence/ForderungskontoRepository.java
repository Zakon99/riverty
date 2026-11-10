package de.riverty.buchungsservice.internal.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;
import jakarta.persistence.LockModeType;

public interface ForderungskontoRepository extends JpaRepository<Forderungskonto, Long> {

    default Forderungskonto findeVorhanden(Long id) {
        return findById(id).orElseThrow(() -> new NichtGefundenException("Forderungskonto " + id + " nicht gefunden."));
    }

    /**
     * Lädt das Konto mit Zeilensperre (SELECT ... FOR UPDATE) für eine Änderung. Parallele Änderungen desselben Kontos
     * warten bis zum Ende der sperrenden Transaktion und lesen danach den aktuellen Stand; so werden alle Buchungen
     * und Verrechnungen eines Kontos serialisiert.
     */
    default Forderungskonto findeVorhandenGesperrt(Long id) {
        return findGesperrtById(id)
                .orElseThrow(() -> new NichtGefundenException("Forderungskonto " + id + " nicht gefunden."));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from Forderungskonto k where k.id = :id")
    Optional<Forderungskonto> findGesperrtById(Long id);

    Optional<Forderungskonto> findByAktenzeichen(String aktenzeichen);
}
