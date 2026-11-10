package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import de.riverty.buchungsservice.internal.domain.Betrag;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.KonfliktException;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.persistence.ForderungskontoRepository;
import de.riverty.buchungsservice.internal.persistence.KontoDebitorRepository;
import de.riverty.buchungsservice.internal.persistence.VerrechnungRepository;

@Service
public class VerrechnungService {

    private final ForderungskontoRepository kontoRepository;
    private final KontoDebitorRepository kontoDebitorRepository;
    private final VerrechnungRepository verrechnungRepository;
    private final Verrechner verrechner;
    private final TransactionTemplate transaktion;
    private final Clock clock;

    VerrechnungService(ForderungskontoRepository kontoRepository, KontoDebitorRepository kontoDebitorRepository,
            VerrechnungRepository verrechnungRepository, Verrechner verrechner, TransactionTemplate transaktion,
            Clock clock) {
        this.kontoRepository = kontoRepository;
        this.kontoDebitorRepository = kontoDebitorRepository;
        this.verrechnungRepository = verrechnungRepository;
        this.verrechner = verrechner;
        this.transaktion = transaktion;
        this.clock = clock;
    }

    /**
     * Verteilt vorhandenes Guthaben aller Zahlungen (älteste Valuta zuerst) nach § 367 BGB auf die offenen Posten.
     * Gibt es nichts zu verteilen, ist der Aufruf ein No-op und liefert eine leere Liste.
     */
    public List<Verrechnung> verrechnen(Long kontoId) {
        try {
            return transaktion.execute(status -> guthabenVerteilen(kontoId));
        } catch (ConcurrencyFailureException e) {
            throw new KonfliktException("Das Konto wurde parallel geändert. Bitte die Verrechnung wiederholen.", e);
        }
    }

    /** Offene Posten (offen > 0) in der Reihenfolge, in der sie nach § 367 BGB bedient werden. */
    @Transactional(readOnly = true)
    public List<OffenerPosten> offenePosten(Long kontoId) {
        kontoRepository.findeVorhanden(kontoId);
        return verrechner.offenePosten(kontoId).stream().map(VerrechnungService::alsOffenerPosten).toList();
    }

    /** Guthaben je zugeordnetem Debitor (auch 0.00) aus seinen nicht stornierten Zahlungen auf diesem Konto. */
    @Transactional(readOnly = true)
    public List<DebitorGuthaben> guthaben(Long kontoId) {
        kontoRepository.findeVorhanden(kontoId);
        Map<Long, BigDecimal> guthabenJeDebitor = verrechner.guthabenJeDebitor(kontoId);
        return kontoDebitorRepository.findByKontoIdOrderById(kontoId).stream()
                .map(zuordnung -> zuordnung.getDebitor().getId())
                .map(id -> new DebitorGuthaben(id, guthabenJeDebitor.getOrDefault(id, Betrag.NULLWERT)))
                .toList();
    }

    /** Alle Verrechnungen des Kontos einschließlich Aufhebungen, nach Id. */
    @Transactional(readOnly = true)
    public List<Verrechnung> verrechnungen(Long kontoId) {
        kontoRepository.findeVorhanden(kontoId);
        return verrechnungRepository.findBySollBuchungKontoIdOrderById(kontoId);
    }

    private List<Verrechnung> guthabenVerteilen(Long kontoId) {
        kontoRepository.findeVorhandenGesperrt(kontoId);
        List<OffenerRest> posten = verrechner.offenePosten(kontoId);
        List<OffenerRest> guthaben = verrechner.guthaben(kontoId);
        if (posten.isEmpty() || guthaben.isEmpty()) {
            return List.of();
        }
        Instant jetzt = Zeitstempel.jetzt(clock);
        List<Verrechnung> neu = new ArrayList<>();
        guthaben.forEach(zahlung -> neu.addAll(verrechner.verteilen(zahlung, posten, jetzt)));
        return neu;
    }

    private static OffenerPosten alsOffenerPosten(OffenerRest rest) {
        Buchung buchung = rest.buchung();
        return new OffenerPosten(buchung.getId(), buchung.getArt(), buchung.getBetrag().wert(), rest.betrag(),
                buchung.getValuta(), buchung.getBuchungszeit());
    }
}
