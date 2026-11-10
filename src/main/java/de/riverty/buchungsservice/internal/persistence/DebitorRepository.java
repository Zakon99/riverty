package de.riverty.buchungsservice.internal.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;

public interface DebitorRepository extends JpaRepository<Debitor, Long> {

    default Debitor findeVorhanden(Long id) {
        return findById(id).orElseThrow(() -> new NichtGefundenException("Debitor " + id + " nicht gefunden."));
    }

    Optional<Debitor> findByDublettenschluessel(String dublettenschluessel);
}
