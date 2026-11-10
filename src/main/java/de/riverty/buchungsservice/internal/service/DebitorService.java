package de.riverty.buchungsservice.internal.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.persistence.DebitorRepository;

@Service
public class DebitorService {

    private final DebitorRepository debitorRepository;
    private final EindeutigesAnlegen eindeutigesAnlegen;

    DebitorService(DebitorRepository debitorRepository, EindeutigesAnlegen eindeutigesAnlegen) {
        this.debitorRepository = debitorRepository;
        this.eindeutigesAnlegen = eindeutigesAnlegen;
    }

    public Debitor anlegen(String vorname, String nachname, LocalDate geburtsdatum, String plz) {
        Debitor debitor = Debitor.anlegen(vorname, nachname, geburtsdatum, plz);
        return eindeutigesAnlegen.anlegen(
                () -> debitorRepository.findByDublettenschluessel(debitor.getDublettenschluessel()).map(Debitor::getId),
                () -> debitorRepository.save(debitor),
                "Ein Debitor mit gleichem Namen, Geburtsdatum und gleicher PLZ existiert bereits.");
    }
}
