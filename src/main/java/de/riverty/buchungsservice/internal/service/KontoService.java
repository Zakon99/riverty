package de.riverty.buchungsservice.internal.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.KontoDebitor;
import de.riverty.buchungsservice.internal.persistence.DebitorRepository;
import de.riverty.buchungsservice.internal.persistence.ForderungskontoRepository;
import de.riverty.buchungsservice.internal.persistence.KontoDebitorRepository;
import de.riverty.buchungsservice.internal.persistence.KreditorRepository;

@Service
public class KontoService {

    /** Bearbeiter der initialen Debitor-Zuordnung bei der Kontoanlage (der Request hat dafür kein Feld). */
    static final String ZUGEORDNET_BEI_KONTOANLAGE = "Kontoanlage";

    private final ForderungskontoRepository kontoRepository;
    private final KreditorRepository kreditorRepository;
    private final DebitorRepository debitorRepository;
    private final KontoDebitorRepository kontoDebitorRepository;
    private final EindeutigesAnlegen eindeutigesAnlegen;
    private final Clock clock;

    KontoService(ForderungskontoRepository kontoRepository, KreditorRepository kreditorRepository,
            DebitorRepository debitorRepository, KontoDebitorRepository kontoDebitorRepository,
            EindeutigesAnlegen eindeutigesAnlegen, Clock clock) {
        this.kontoRepository = kontoRepository;
        this.kreditorRepository = kreditorRepository;
        this.debitorRepository = debitorRepository;
        this.kontoDebitorRepository = kontoDebitorRepository;
        this.eindeutigesAnlegen = eindeutigesAnlegen;
        this.clock = clock;
    }

    /** Legt ein Konto an; ist {@code debitorId} gesetzt, wird dieser Debitor sofort zugeordnet. */
    public Forderungskonto anlegen(String aktenzeichen, Long kreditorId, Long debitorId) {
        String normalisiert = Forderungskonto.normalisiereAktenzeichen(aktenzeichen);
        return eindeutigesAnlegen.anlegen(
                () -> kontoRepository.findByAktenzeichen(normalisiert).map(Forderungskonto::getId),
                () -> eroeffnen(normalisiert, kreditorId, debitorId),
                "Aktenzeichen " + normalisiert + " ist bereits vergeben.");
    }

    public KontoDebitor debitorZuordnen(Long kontoId, Long debitorId, String zugeordnetVon) {
        return eindeutigesAnlegen.anlegen(
                () -> kontoDebitorRepository.findByKontoIdAndDebitorId(kontoId, debitorId).map(KontoDebitor::getId),
                () -> kontoDebitorRepository.save(kontoRepository.findeVorhanden(kontoId)
                        .ordneZu(debitorRepository.findeVorhanden(debitorId), Zeitstempel.jetzt(clock), zugeordnetVon)),
                "Debitor " + debitorId + " ist dem Konto " + kontoId + " bereits zugeordnet.");
    }

    @Transactional(readOnly = true)
    public Forderungskonto laden(Long kontoId) {
        return kontoRepository.findeVorhanden(kontoId);
    }

    private Forderungskonto eroeffnen(String aktenzeichen, Long kreditorId, Long debitorId) {
        Forderungskonto konto = Forderungskonto.eroeffnen(aktenzeichen, kreditorRepository.findeVorhanden(kreditorId));
        if (debitorId != null) {
            konto.ordneZu(debitorRepository.findeVorhanden(debitorId), Zeitstempel.jetzt(clock), ZUGEORDNET_BEI_KONTOANLAGE);
        }
        return kontoRepository.save(konto);
    }
}
