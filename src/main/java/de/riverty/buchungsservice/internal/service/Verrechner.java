package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import de.riverty.buchungsservice.internal.domain.Betrag;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.persistence.BuchungRepository;
import de.riverty.buchungsservice.internal.persistence.NettoVerrechnet;
import de.riverty.buchungsservice.internal.persistence.VerrechnungRepository;

/**
 * Verrechnung nach § 367 BGB. Offene Posten und Guthaben werden je Konto mit zwei Abfragen ermittelt
 * (Buchungen + aggregierte Netto-Verrechnung), ohne N+1.
 */
@Component
class Verrechner {

    private final BuchungRepository buchungRepository;
    private final VerrechnungRepository verrechnungRepository;

    Verrechner(BuchungRepository buchungRepository, VerrechnungRepository verrechnungRepository) {
        this.buchungRepository = buchungRepository;
        this.verrechnungRepository = verrechnungRepository;
    }

    /** Nicht stornierte Sollbuchungen mit offenem Rest, in Tilgungsreihenfolge (Gebühren, Zinsen, Hauptforderung). */
    List<OffenerRest> offenePosten(Long kontoId) {
        return reste(buchungRepository.findeNichtStornierte(kontoId, Buchungsart.sollarten()),
                verrechnungRepository.nettoJeSollbuchung(kontoId), Buchung.TILGUNGSREIHENFOLGE);
    }

    /**
     * Nicht stornierte Zahlungen mit verfügbarem Guthaben, älteste Valuta zuerst. Ausbuchungen sind nie Guthaben.
     */
    List<OffenerRest> guthaben(Long kontoId) {
        return reste(buchungRepository.findeNichtStornierte(kontoId, EnumSet.of(Buchungsart.ZAHLUNG)),
                verrechnungRepository.nettoJeHabenbuchung(kontoId), Buchung.CHRONOLOGISCH);
    }

    /** Verteilt den Rest der Haben-Buchung auf die Posten in deren Reihenfolge und speichert die Verrechnungen. */
    List<Verrechnung> verteilen(OffenerRest haben, List<OffenerRest> posten, Instant zeitpunkt) {
        List<Verrechnung> neu = new ArrayList<>();
        for (OffenerRest einzelposten : posten) {
            if (!haben.istOffen()) {
                break;
            }
            if (einzelposten.istOffen()) {
                BigDecimal betrag = einzelposten.entnehmen(haben.betrag());
                haben.entnehmen(betrag);
                neu.add(Verrechnung.verrechne(haben.buchung(), einzelposten.buchung(), Betrag.of(betrag), zeitpunkt));
            }
        }
        return verrechnungRepository.saveAll(neu);
    }

    private static List<OffenerRest> reste(List<Buchung> buchungen, List<NettoVerrechnet> netto,
            Comparator<Buchung> reihenfolge) {
        Map<Long, BigDecimal> nettoJeBuchung =
                netto.stream().collect(Collectors.toMap(NettoVerrechnet::buchungId, NettoVerrechnet::netto));
        return buchungen.stream()
                .sorted(reihenfolge)
                .map(b -> new OffenerRest(b, offen(b, nettoJeBuchung)))
                .filter(OffenerRest::istOffen)
                .toList();
    }

    private static BigDecimal offen(Buchung buchung, Map<Long, BigDecimal> nettoJeBuchung) {
        return buchung.getBetrag().wert().subtract(nettoJeBuchung.getOrDefault(buchung.getId(), Betrag.NULLWERT));
    }
}
