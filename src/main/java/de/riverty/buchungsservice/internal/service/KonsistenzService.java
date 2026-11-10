package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.riverty.buchungsservice.internal.domain.Betrag;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.persistence.BuchungRepository;
import de.riverty.buchungsservice.internal.persistence.ForderungskontoRepository;
import de.riverty.buchungsservice.internal.persistence.NettoVerrechnet;
import de.riverty.buchungsservice.internal.persistence.VerrechnungRepository;

/**
 * Prüft die Invarianten eines Kontos: (1) gespeicherter Saldo = Saldo aus allen Buchungen, (2a) kein Sollposten
 * überbezahlt, (2b) keine Zahlung oder Ausbuchung überverteilt, (2c) keine Buchung mit negativem
 * Netto-Verrechnungsbetrag. Jede Abweichung wird als Error geloggt.
 */
@Service
public class KonsistenzService {

    private static final Logger log = LoggerFactory.getLogger(KonsistenzService.class);

    private final ForderungskontoRepository kontoRepository;
    private final BuchungRepository buchungRepository;
    private final VerrechnungRepository verrechnungRepository;

    KonsistenzService(ForderungskontoRepository kontoRepository, BuchungRepository buchungRepository,
            VerrechnungRepository verrechnungRepository) {
        this.kontoRepository = kontoRepository;
        this.buchungRepository = buchungRepository;
        this.verrechnungRepository = verrechnungRepository;
    }

    @Transactional(readOnly = true)
    public Konsistenzbericht pruefen(Long kontoId) {
        Forderungskonto konto = kontoRepository.findeVorhanden(kontoId);
        List<Abweichung> abweichungen = new ArrayList<>();
        pruefeSaldo(konto, abweichungen);
        List<NettoVerrechnet> soll = verrechnungRepository.nettoJeSollbuchung(kontoId);
        List<NettoVerrechnet> haben = verrechnungRepository.nettoJeHabenbuchung(kontoId);
        abweichungen.addAll(ueberverteilt(Pruefung.POSTEN_UEBERBEZAHLT, soll));
        abweichungen.addAll(ueberverteilt(Pruefung.HABEN_UEBERVERTEILT, haben));
        abweichungen.addAll(negativ(soll));
        abweichungen.addAll(negativ(haben));
        abweichungen.forEach(abweichung -> log.error(
                "Konsistenzabweichung {} auf Konto {} (Buchung {}): erwartet {}, ist {}, Differenz {}",
                abweichung.pruefung(), kontoId, abweichung.buchungId(), abweichung.erwartet(), abweichung.ist(),
                abweichung.differenz()));
        KonsistenzStatus status = abweichungen.isEmpty() ? KonsistenzStatus.OK : KonsistenzStatus.ABWEICHUNG;
        return new Konsistenzbericht(status, List.copyOf(abweichungen));
    }

    private void pruefeSaldo(Forderungskonto konto, List<Abweichung> abweichungen) {
        BigDecimal erwartet = buchungRepository.findByKontoIdOrderByValutaAscBuchungszeitAscIdAsc(konto.getId())
                .stream()
                .map(Buchung::saldoWirkung)
                .reduce(Betrag.NULLWERT, BigDecimal::add);
        if (konto.getSaldo().compareTo(erwartet) != 0) {
            abweichungen.add(Abweichung.von(Pruefung.SALDO, null, erwartet, konto.getSaldo()));
        }
    }

    /** Netto verrechnet (Verrechnungen − Aufhebungen) darf den Buchungsbetrag nicht übersteigen. */
    private static List<Abweichung> ueberverteilt(Pruefung pruefung, List<NettoVerrechnet> netto) {
        return netto.stream()
                .filter(n -> n.netto().compareTo(n.betrag()) > 0)
                .map(n -> Abweichung.von(pruefung, n.buchungId(), n.betrag(), n.netto()))
                .toList();
    }

    /** Netto verrechnet darf nicht unter 0.00 liegen (nie mehr aufgehoben als verrechnet). */
    private static List<Abweichung> negativ(List<NettoVerrechnet> netto) {
        return netto.stream()
                .filter(n -> n.netto().signum() < 0)
                .map(n -> Abweichung.von(Pruefung.VERRECHNUNG_NEGATIV, n.buchungId(), Betrag.NULLWERT, n.netto()))
                .toList();
    }
}
