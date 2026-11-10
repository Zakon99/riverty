package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.support.IntegrationstestBasis;
import de.riverty.buchungsservice.support.MutableClock;

/**
 * Gemeinsame Basis fuer Service-Tests gegen die echte H2-In-Memory-DB (Profil {@code test}).
 * Das Leeren der Fachtabellen, das Zuruecksetzen der Clock auf {@link MutableClock#START} und {@code parallel(...)}
 * stammen aus {@link IntegrationstestBasis} (bewusst ohne {@code @Transactional}).
 */
abstract class ServiceTestBasis extends IntegrationstestBasis {

    /** Id, die in keinem Test vergeben wird. */
    static final Long UNBEKANNT = 999_999L;

    @Autowired
    protected KreditorService kreditorService;
    @Autowired
    protected DebitorService debitorService;
    @Autowired
    protected KontoService kontoService;
    @Autowired
    protected BuchungService buchungService;
    @Autowired
    protected VerrechnungService verrechnungService;
    @Autowired
    protected KonsistenzService konsistenzService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    protected Testdaten testdaten;

    @BeforeEach
    void serviceTestdatenAufsetzen() {
        testdaten = new Testdaten(kreditorService, debitorService, kontoService, buchungService, clock);
    }

    static BigDecimal eur(String wert) {
        return new BigDecimal(wert);
    }

    BigDecimal saldo(Long kontoId) {
        return kontoService.laden(kontoId).getSaldo();
    }

    BigDecimal offen(Long kontoId, Buchung sollBuchung) {
        return verrechnungService.offenePosten(kontoId).stream()
                .filter(p -> p.buchungId().equals(sollBuchung.getId()))
                .map(OffenerPosten::offen)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    List<Long> offenePostenIds(Long kontoId) {
        return verrechnungService.offenePosten(kontoId).stream().map(OffenerPosten::buchungId).toList();
    }

    BigDecimal guthaben(Long kontoId, Debitor debitor) {
        return verrechnungService.guthaben(kontoId).stream()
                .filter(g -> g.debitorId().equals(debitor.getId()))
                .map(DebitorGuthaben::guthaben)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein Guthaben-Eintrag fuer Debitor " + debitor.getId()));
    }

    List<Verrechnung> verrechnungenVon(Long kontoId, Buchung habenBuchung) {
        return verrechnungService.verrechnungen(kontoId).stream()
                .filter(v -> v.getHabenBuchung().getId().equals(habenBuchung.getId()))
                .toList();
    }

    List<Verrechnung> verrechnungenAusgeloestDurch(Long kontoId, Buchung ausloeser) {
        return verrechnungService.verrechnungen(kontoId).stream()
                .filter(v -> v.getAusgeloestDurch() != null && v.getAusgeloestDurch().getId().equals(ausloeser.getId()))
                .toList();
    }

    int anzahlBuchungen(Long kontoId) {
        return buchungService.historie(kontoId).size();
    }

    void assertKonsistent(Long kontoId) {
        Konsistenzbericht bericht = konsistenzService.pruefen(kontoId);
        assertThat(bericht.status()).isEqualTo(KonsistenzStatus.OK);
        assertThat(bericht.abweichungen()).isEmpty();
        assertThat(saldo(kontoId)).isEqualByComparingTo(berechneterSaldo(kontoId));
    }

    /** Saldo unabhaengig vom Produktivcode aus der Historie: Σ Soll − Σ Haben, Storno auf der Gegenseite des Originals. */
    BigDecimal berechneterSaldo(Long kontoId) {
        List<Buchung> historie = buchungService.historie(kontoId);
        Map<Long, Buchungsart> artNachId = historie.stream()
                .collect(Collectors.toMap(Buchung::getId, Buchung::getArt));
        BigDecimal summe = BigDecimal.ZERO;
        for (Buchung buchung : historie) {
            boolean soll;
            if (buchung.getArt() == Buchungsart.STORNO) {
                soll = !artNachId.get(buchung.getStornoVon().getId()).istSollart();
            } else {
                soll = buchung.getArt().istSollart();
            }
            summe = soll ? summe.add(buchung.getBetrag().wert()) : summe.subtract(buchung.getBetrag().wert());
        }
        return summe;
    }

    /** Liest Zuordnungen eines Kontos innerhalb einer Transaktion (Lazy-Relationen). */
    List<Zuordnung> zuordnungen(Long kontoId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Zuordnung> ergebnis = tx.execute(status -> kontoService.laden(kontoId).getDebitoren().stream()
                .map(kd -> new Zuordnung(kd.getDebitor().getId(), kd.getZugeordnetAm(), kd.getZugeordnetVon()))
                .toList());
        return ergebnis == null ? List.of() : ergebnis;
    }

    /** Momentaufnahme aller Buchungen und Verrechnungen (S1-AK-49). */
    Momentaufnahme momentaufnahme() {
        return new Momentaufnahme(zeilenNachId("buchung"), zeilenNachId("verrechnung"));
    }

    /** S1-AK-49: Keine bestehende Buchung/Verrechnung wurde geaendert oder geloescht. */
    void assertNurNeueDatensaetze(Momentaufnahme vorher) {
        Momentaufnahme nachher = momentaufnahme();
        assertThat(nachher.buchungen()).containsAllEntriesOf(vorher.buchungen());
        assertThat(nachher.verrechnungen()).containsAllEntriesOf(vorher.verrechnungen());
    }

    private Map<Object, Map<String, Object>> zeilenNachId(String tabelle) {
        return jdbc.queryForList("SELECT * FROM " + tabelle).stream().collect(Collectors.toMap(z -> z.get("id"), Function.identity()));
    }

    record Zuordnung(Long debitorId, Instant zugeordnetAm, String zugeordnetVon) {
    }

    record Momentaufnahme(Map<Object, Map<String, Object>> buchungen, Map<Object, Map<String, Object>> verrechnungen) {
    }
}
