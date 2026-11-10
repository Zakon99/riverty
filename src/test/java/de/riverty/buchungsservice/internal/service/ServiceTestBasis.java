package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.Verrechnung;
import de.riverty.buchungsservice.support.MutableClock;
import de.riverty.buchungsservice.support.TestClockKonfiguration;

/**
 * Gemeinsame Basis fuer Service-Tests gegen die echte H2-In-Memory-DB (Profil {@code test}).
 * Bewusst ohne {@code @Transactional}: Die Transaktionen der Services sollen unveraendert wirken.
 * Vor jedem Test werden alle Fachtabellen geleert und die Clock auf {@link MutableClock#START} gesetzt.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockKonfiguration.class)
abstract class ServiceTestBasis {

    /** Id, die in keinem Test vergeben wird. */
    static final Long UNBEKANNT = 999_999L;

    private static final List<String> TABELLEN =
            List.of("verrechnung", "buchung", "konto_debitor", "forderungskonto", "debitor", "kreditor");

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
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    private PlatformTransactionManager transactionManager;

    protected Testdaten testdaten;

    @BeforeEach
    void basisAufsetzen() {
        datenbankLeeren();
        clock.zuruecksetzen();
        testdaten = new Testdaten(kreditorService, debitorService, kontoService, buchungService, clock);
    }

    private void datenbankLeeren() {
        // Selbstreferenzen (stornoVon, aufhebungVon) duerfen das Leeren nicht blockieren.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            TABELLEN.forEach(tabelle -> jdbc.execute("DELETE FROM " + tabelle));
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
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

    int anzahl(String tabelle) {
        Integer anzahl = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabelle, Integer.class);
        return anzahl == null ? 0 : anzahl;
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

    /**
     * Startet beide Aufrufe moeglichst gleichzeitig (Startsignal per Latch) und sammelt je Aufruf Ergebnis oder Fehler,
     * in der Reihenfolge der Parameter. Haengt ein Aufruf laenger als 30 Sekunden, schlaegt der Test fehl.
     */
    <T> List<Ausgang<T>> parallel(Callable<T> erster, Callable<T> zweiter) throws InterruptedException {
        CountDownLatch bereit = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Ausgang<T>> ausgaenge = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> aufruf : List.of(erster, zweiter)) {
                futures.add(executor.submit(() -> {
                    bereit.countDown();
                    start.await();
                    return aufruf.call();
                }));
            }
            assertThat(bereit.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<T> future : futures) {
                try {
                    ausgaenge.add(new Ausgang<>(future.get(30, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    ausgaenge.add(new Ausgang<>(null, e.getCause()));
                } catch (TimeoutException e) {
                    throw new AssertionError("Paralleler Aufruf haengt", e);
                }
            }
        }
        return ausgaenge;
    }

    record Ausgang<T>(T ergebnis, Throwable fehler) {
    }

    record Zuordnung(Long debitorId, Instant zugeordnetAm, String zugeordnetVon) {
    }

    record Momentaufnahme(Map<Object, Map<String, Object>> buchungen, Map<Object, Map<String, Object>> verrechnungen) {
    }
}
