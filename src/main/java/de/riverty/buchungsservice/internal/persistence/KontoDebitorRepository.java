package de.riverty.buchungsservice.internal.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import de.riverty.buchungsservice.internal.domain.KontoDebitor;

public interface KontoDebitorRepository extends JpaRepository<KontoDebitor, Long> {

    Optional<KontoDebitor> findByKontoIdAndDebitorId(Long kontoId, Long debitorId);

    boolean existsByKontoIdAndDebitorId(Long kontoId, Long debitorId);

    List<KontoDebitor> findByKontoIdOrderById(Long kontoId);
}
