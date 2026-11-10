package de.riverty.buchungsservice.internal.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;

public interface KreditorRepository extends JpaRepository<Kreditor, Long> {

    default Kreditor findeVorhanden(Long id) {
        return findById(id).orElseThrow(() -> new NichtGefundenException("Kreditor " + id + " nicht gefunden."));
    }

    Optional<Kreditor> findByKreditorennummer(String kreditorennummer);
}
