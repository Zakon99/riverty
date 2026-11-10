package de.riverty.buchungsservice.internal.service;

import org.springframework.stereotype.Service;

import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.persistence.KreditorRepository;

@Service
public class KreditorService {

    private final KreditorRepository kreditorRepository;
    private final EindeutigesAnlegen eindeutigesAnlegen;

    KreditorService(KreditorRepository kreditorRepository, EindeutigesAnlegen eindeutigesAnlegen) {
        this.kreditorRepository = kreditorRepository;
        this.eindeutigesAnlegen = eindeutigesAnlegen;
    }

    public Kreditor anlegen(String kreditorennummer, String name) {
        Kreditor kreditor = Kreditor.anlegen(kreditorennummer, name);
        return eindeutigesAnlegen.anlegen(
                () -> kreditorRepository.findByKreditorennummer(kreditor.getKreditorennummer()).map(Kreditor::getId),
                () -> kreditorRepository.save(kreditor),
                "Kreditorennummer " + kreditor.getKreditorennummer() + " ist bereits vergeben.");
    }
}
