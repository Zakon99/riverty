package de.riverty.buchungsservice.internal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.riverty.buchungsservice.internal.domain.Betrag;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.KontoDebitor;
import de.riverty.buchungsservice.internal.domain.UngueltigeSucheException;
import de.riverty.buchungsservice.internal.persistence.BuchungRepository;
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
    private final BuchungRepository buchungRepository;
    private final Verrechner verrechner;
    private final EindeutigesAnlegen eindeutigesAnlegen;
    private final Clock clock;

    KontoService(ForderungskontoRepository kontoRepository, KreditorRepository kreditorRepository,
            DebitorRepository debitorRepository, KontoDebitorRepository kontoDebitorRepository,
            BuchungRepository buchungRepository, Verrechner verrechner, EindeutigesAnlegen eindeutigesAnlegen,
            Clock clock) {
        this.kontoRepository = kontoRepository;
        this.kreditorRepository = kreditorRepository;
        this.debitorRepository = debitorRepository;
        this.kontoDebitorRepository = kontoDebitorRepository;
        this.buchungRepository = buchungRepository;
        this.verrechner = verrechner;
        this.eindeutigesAnlegen = eindeutigesAnlegen;
        this.clock = clock;
    }

    /** Legt ein Konto an; ist {@code debitorId} gesetzt, wird dieser Debitor sofort zugeordnet. */
    public Forderungskonto anlegen(String aktenzeichen, Long kreditorId, Long debitorId) {
        return anlegen(aktenzeichen, kreditorId, debitorId, Function.identity());
    }

    /**
     * Wie {@link #anlegen(String, Long, Long)}, liefert aber die Übersicht des neuen Kontos; sie wird in derselben
     * Transaktion wie die Anlage gebildet.
     */
    public KontoUebersicht anlegenMitUebersicht(String aktenzeichen, Long kreditorId, Long debitorId) {
        return anlegen(aktenzeichen, kreditorId, debitorId, KontoUebersicht::von);
    }

    /** Ordnet den Debitor zu; ist er bereits zugeordnet, trägt die {@code DuplikatException} die Debitor-Id. */
    public KontoDebitor debitorZuordnen(Long kontoId, Long debitorId, String zugeordnetVon) {
        return eindeutigesAnlegen.anlegen(
                () -> kontoDebitorRepository.findByKontoIdAndDebitorId(kontoId, debitorId).map(z -> debitorId),
                () -> kontoDebitorRepository.save(kontoRepository.findeVorhanden(kontoId)
                        .ordneZu(debitorRepository.findeVorhanden(debitorId), Zeitstempel.jetzt(clock), zugeordnetVon)),
                "Debitor " + debitorId + " ist dem Konto " + kontoId + " bereits zugeordnet.");
    }

    @Transactional(readOnly = true)
    public Forderungskonto laden(Long kontoId) {
        return kontoRepository.findeVorhanden(kontoId);
    }

    /**
     * Sucht Konten nach Aktenzeichen und/oder Kreditorennummer: getrimmt, exakter Treffer, UND-verknüpft, nach Id
     * sortiert. Ein leerer Parameter ({@code null} oder nur Leerzeichen) schränkt nicht ein; mindestens einer muss
     * gefüllt sein, sonst {@link UngueltigeSucheException}.
     */
    @Transactional(readOnly = true)
    public List<KontoUebersicht> suchen(String aktenzeichen, String kreditorennummer) {
        String aktenzeichenSuche = suchwert(aktenzeichen);
        String kreditorennummerSuche = suchwert(kreditorennummer);
        if (aktenzeichenSuche == null && kreditorennummerSuche == null) {
            throw new UngueltigeSucheException("Mindestens aktenzeichen oder kreditorennummer muss angegeben sein.");
        }
        return kontoRepository.suche(aktenzeichenSuche, kreditorennummerSuche).stream()
                .map(KontoUebersicht::von)
                .toList();
    }

    /** Kontoansicht mit Kreditor, zugeordneten Debitoren inklusive Guthaben und der vollständigen Historie. */
    @Transactional(readOnly = true)
    public Kontoansicht ansicht(Long kontoId) {
        Forderungskonto konto = kontoRepository.findeVorhandenMitZuordnungen(kontoId);
        Map<Long, BigDecimal> guthaben = verrechner.guthabenJeDebitor(kontoId);
        List<ZugeordneterDebitor> debitoren = konto.getDebitoren().stream()
                .map(z -> new ZugeordneterDebitor(z.getDebitor(), z.getZugeordnetAm(), z.getZugeordnetVon(),
                        guthaben.getOrDefault(z.getDebitor().getId(), Betrag.NULLWERT)))
                .toList();
        return new Kontoansicht(konto.getId(), konto.getAktenzeichen(), konto.getSaldo(), konto.getKreditor(),
                debitoren, buchungRepository.findByKontoIdOrderByValutaAscBuchungszeitAscIdAsc(kontoId));
    }

    private <T> T anlegen(String aktenzeichen, Long kreditorId, Long debitorId, Function<Forderungskonto, T> ergebnis) {
        String normalisiert = Forderungskonto.normalisiereAktenzeichen(aktenzeichen);
        return eindeutigesAnlegen.anlegen(
                () -> kontoRepository.findByAktenzeichen(normalisiert).map(Forderungskonto::getId),
                () -> ergebnis.apply(eroeffnen(normalisiert, kreditorId, debitorId)),
                "Aktenzeichen " + normalisiert + " ist bereits vergeben.");
    }

    private Forderungskonto eroeffnen(String aktenzeichen, Long kreditorId, Long debitorId) {
        Forderungskonto konto = Forderungskonto.eroeffnen(aktenzeichen, kreditorRepository.findeVorhanden(kreditorId));
        if (debitorId != null) {
            konto.ordneZu(debitorRepository.findeVorhanden(debitorId), Zeitstempel.jetzt(clock), ZUGEORDNET_BEI_KONTOANLAGE);
        }
        return kontoRepository.save(konto);
    }

    private static String suchwert(String parameter) {
        return parameter == null || parameter.isBlank() ? null : parameter.strip();
    }
}
