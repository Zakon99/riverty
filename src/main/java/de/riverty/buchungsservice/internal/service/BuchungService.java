package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.hibernate.Hibernate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import de.riverty.buchungsservice.internal.domain.AnfrageKennung;
import de.riverty.buchungsservice.internal.domain.Begruendung;
import de.riverty.buchungsservice.internal.domain.Betrag;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.FachlicheRegelVerletztException;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.KonfliktException;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.internal.persistence.BuchungRepository;
import de.riverty.buchungsservice.internal.persistence.DebitorRepository;
import de.riverty.buchungsservice.internal.persistence.ForderungskontoRepository;
import de.riverty.buchungsservice.internal.persistence.KontoDebitorRepository;
import de.riverty.buchungsservice.internal.persistence.VerrechnungRepository;

/**
 * Schreibende Buchungsvorgänge, alle idempotent über die clientRequestId.
 * <p>
 * Jede Buchung läuft in einer eigenen Transaktion, die das Konto zuerst pessimistisch sperrt; Buchungen auf demselben
 * Konto werden dadurch serialisiert. Die Methoden sind bewusst nicht als Ganzes transaktional: Scheitert die Transaktion
 * an einer parallel angelegten Buchung mit derselben clientRequestId (Unique-Constraint) oder an einer Sperre, wird
 * außerhalb der gescheiterten Transaktion neu gelesen und über Replay oder Konflikt entschieden.
 */
@Service
public class BuchungService {

    private enum Operation { SOLLBUCHUNG, ZAHLUNG, STORNO, AUSBUCHUNG }

    private final ForderungskontoRepository kontoRepository;
    private final DebitorRepository debitorRepository;
    private final KontoDebitorRepository kontoDebitorRepository;
    private final BuchungRepository buchungRepository;
    private final VerrechnungRepository verrechnungRepository;
    private final Verrechner verrechner;
    private final TransactionTemplate transaktion;
    private final Clock clock;

    BuchungService(ForderungskontoRepository kontoRepository, DebitorRepository debitorRepository,
            KontoDebitorRepository kontoDebitorRepository, BuchungRepository buchungRepository,
            VerrechnungRepository verrechnungRepository, Verrechner verrechner, TransactionTemplate transaktion,
            Clock clock) {
        this.kontoRepository = kontoRepository;
        this.debitorRepository = debitorRepository;
        this.kontoDebitorRepository = kontoDebitorRepository;
        this.buchungRepository = buchungRepository;
        this.verrechnungRepository = verrechnungRepository;
        this.verrechner = verrechner;
        this.transaktion = transaktion;
        this.clock = clock;
    }

    public BuchungsErgebnis sollbuchen(Long kontoId, SollbuchungBefehl befehl) {
        Betrag betrag = Betrag.of(befehl.betrag());
        AnfrageKennung anfrage = RequestHash.kennung(befehl.clientRequestId(),
                Operation.SOLLBUCHUNG, kontoId, befehl.art(), betrag, befehl.valuta());
        return idempotent(kontoId, anfrage, konto -> {
            // Der Kreditor wird mitgeladen, damit das Ergebnis auch außerhalb der Transaktion lesbar ist.
            Hibernate.initialize(konto.getKreditor());
            Instant jetzt = Zeitstempel.jetzt(clock);
            return buchen(konto, Buchung.sollbuchung(konto, befehl.art(), betrag, befehl.valuta(), jetzt, anfrage));
        });
    }

    /** Bucht die Zahlung und verteilt nur diese Zahlung nach § 367 BGB; ein Rest bleibt Guthaben des Zahlers. */
    public BuchungsErgebnis zahlen(Long kontoId, ZahlungBefehl befehl) {
        Betrag betrag = Betrag.of(befehl.betrag());
        AnfrageKennung anfrage = RequestHash.kennung(befehl.clientRequestId(),
                Operation.ZAHLUNG, kontoId, befehl.debitorId(), betrag, befehl.valuta());
        return idempotent(kontoId, anfrage, konto -> {
            Debitor debitor = zugeordneterDebitor(konto, befehl.debitorId());
            Instant jetzt = Zeitstempel.jetzt(clock);
            Buchung zahlung = buchen(konto, Buchung.zahlung(konto, debitor, betrag, befehl.valuta(), jetzt, anfrage));
            verrechner.verteilen(new OffenerRest(zahlung, betrag.wert()), verrechner.offenePosten(kontoId), jetzt);
            return zahlung;
        });
    }

    /** Storniert eine Buchung und hebt ihre aktiven Verrechnungen auf; es wird nichts neu verteilt. */
    public BuchungsErgebnis stornieren(Long kontoId, Long buchungId, StornoBefehl befehl) {
        Begruendung begruendung = new Begruendung(befehl.grund(), befehl.bemerkung(), befehl.ausloeser());
        AnfrageKennung anfrage = RequestHash.kennung(befehl.clientRequestId(),
                Operation.STORNO, kontoId, buchungId, befehl.grund(), befehl.bemerkung(), befehl.ausloeser());
        return idempotent(kontoId, anfrage, konto -> {
            Buchung original = buchungRepository.findeVorhanden(buchungId);
            List<Verrechnung> aktive = pruefeStornierbar(konto, original);
            Instant jetzt = Zeitstempel.jetzt(clock);
            Buchung storno = buchen(konto, original.storniere(begruendung, jetzt, anfrage));
            verrechnungRepository.saveAll(aktive.stream().map(v -> v.hebeAuf(storno, jetzt)).toList());
            return storno;
        });
    }

    /** Bucht den gesamten offenen Rest des Kontos aus (alle Valuten) und verrechnet ihn nach § 367 BGB. */
    public BuchungsErgebnis ausbuchen(Long kontoId, AusbuchungBefehl befehl) {
        Begruendung begruendung = new Begruendung(befehl.grund(), befehl.bemerkung(), befehl.ausloeser());
        AnfrageKennung anfrage = RequestHash.kennung(befehl.clientRequestId(),
                Operation.AUSBUCHUNG, kontoId, befehl.grund(), befehl.bemerkung(), befehl.ausloeser());
        return idempotent(kontoId, anfrage, konto -> {
            List<OffenerRest> posten = verrechner.offenePosten(kontoId);
            Betrag offen = auszubuchenderBetrag(kontoId, posten);
            Instant jetzt = Zeitstempel.jetzt(clock);
            Buchung ausbuchung = buchen(konto, Buchung.ausbuchung(konto, offen, begruendung, jetzt, anfrage));
            verrechner.verteilen(new OffenerRest(ausbuchung, offen.wert()), posten, jetzt);
            return ausbuchung;
        });
    }

    /** Alle Buchungen des Kontos, sortiert nach Valuta, Buchungszeit, Id. */
    @Transactional(readOnly = true)
    public List<Buchung> historie(Long kontoId) {
        kontoRepository.findeVorhanden(kontoId);
        return buchungRepository.findByKontoIdOrderByValutaAscBuchungszeitAscIdAsc(kontoId);
    }

    /**
     * Führt die Buchung idempotent aus. Ein vorhandenes Original wird ohne Sperre erkannt. Sonst wird das Konto in einer
     * eigenen Transaktion gesperrt und danach erneut geprüft: Eine parallele Anfrage mit derselben clientRequestId auf
     * demselben Konto hat dann bereits committet und wird als Replay bzw. Konflikt erkannt. Nur parallele Anfragen auf
     * verschiedenen Konten scheitern erst am Unique-Constraint; dann wird außerhalb der gescheiterten Transaktion neu
     * gelesen.
     */
    private BuchungsErgebnis idempotent(Long kontoId, AnfrageKennung anfrage, Function<Forderungskonto, Buchung> buchung) {
        Optional<BuchungsErgebnis> replay = replay(anfrage);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transaktion.execute(status -> {
                Forderungskonto konto = kontoRepository.findeVorhandenGesperrt(kontoId);
                return replay(anfrage).orElseGet(() -> new BuchungsErgebnis(buchung.apply(konto), true));
            });
        } catch (DataIntegrityViolationException e) {
            return replay(anfrage).orElseThrow(() -> e);
        } catch (ConcurrencyFailureException e) {
            return replay(anfrage).orElseThrow(() -> new KonfliktException(
                    "Das Konto wurde parallel geändert. Bitte die Anfrage wiederholen.", e));
        }
    }

    /** Gleiche clientRequestId und gleicher Inhalt: Replay der Originalbuchung; anderer Inhalt: Konflikt. */
    private Optional<BuchungsErgebnis> replay(AnfrageKennung anfrage) {
        return buchungRepository.findByClientRequestId(anfrage.clientRequestId()).map(vorhanden -> {
            if (!anfrage.gleicherInhaltWie(vorhanden)) {
                throw new KonfliktException("clientRequestId " + anfrage.clientRequestId()
                        + " wurde bereits für eine Anfrage mit anderem Inhalt verwendet.");
            }
            return new BuchungsErgebnis(vorhanden, false);
        });
    }

    private Buchung buchen(Forderungskonto konto, Buchung buchung) {
        Buchung gespeichert = buchungRepository.save(buchung);
        konto.buche(gespeichert);
        return gespeichert;
    }

    private Debitor zugeordneterDebitor(Forderungskonto konto, Long debitorId) {
        if (debitorId == null) {
            throw new FachlicheRegelVerletztException("Eine Zahlung muss einem Debitor zugeordnet sein.");
        }
        Debitor debitor = debitorRepository.findeVorhanden(debitorId);
        if (!kontoDebitorRepository.existsByKontoIdAndDebitorId(konto.getId(), debitorId)) {
            throw new FachlicheRegelVerletztException(
                    "Debitor " + debitorId + " ist dem Konto " + konto.getId() + " nicht zugeordnet.");
        }
        return debitor;
    }

    /** Prüft die Storno-Regeln und liefert die aktiven Verrechnungen des Originals, die aufzuheben sind. */
    private List<Verrechnung> pruefeStornierbar(Forderungskonto konto, Buchung original) {
        if (!original.gehoertZu(konto)) {
            throw new FachlicheRegelVerletztException(
                    "Buchung " + original.getId() + " gehört nicht zum Konto " + konto.getId() + ".");
        }
        if (buchungRepository.existsByStornoVonId(original.getId())) {
            throw new KonfliktException("Buchung " + original.getId() + " ist bereits storniert.");
        }
        List<Verrechnung> aktive = verrechnungRepository.findeAktive(original);
        if (original.getArt().istSollart() && aktive.stream()
                .anyMatch(v -> v.getHabenBuchung().getArt() == Buchungsart.AUSBUCHUNG)) {
            throw new FachlicheRegelVerletztException("Auf Buchung " + original.getId()
                    + " ist eine Ausbuchung verrechnet. Zuerst muss die Ausbuchung storniert werden.");
        }
        return aktive;
    }

    private Betrag auszubuchenderBetrag(Long kontoId, List<OffenerRest> posten) {
        if (posten.isEmpty()) {
            throw new FachlicheRegelVerletztException("Auf dem Konto " + kontoId + " ist nichts offen.");
        }
        if (verrechner.guthaben(kontoId).stream().anyMatch(OffenerRest::istOffen)) {
            throw new FachlicheRegelVerletztException("Auf dem Konto " + kontoId
                    + " ist noch Guthaben vorhanden. Zuerst muss das Guthaben verrechnet werden.");
        }
        return Betrag.of(posten.stream().map(OffenerRest::betrag).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
