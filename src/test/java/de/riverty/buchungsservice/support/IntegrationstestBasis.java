package de.riverty.buchungsservice.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gemeinsame Basis aller Integrationstests gegen die H2-In-Memory-DB (Profil {@code test}), fuer Service- und
 * REST-Tests (ein gemeinsamer, gecachter Spring-Kontext inklusive MockMvc).
 * Bewusst ohne {@code @Transactional}: Die Transaktionen der Anwendung sollen unveraendert wirken.
 * Vor jedem Test werden alle Fachtabellen geleert und die Clock auf {@link MutableClock#START} (UTC) gesetzt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockKonfiguration.class)
public abstract class IntegrationstestBasis {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void datenbankUndUhrZuruecksetzen() {
        Datenbank.leeren(jdbc);
        clock.zuruecksetzen();
    }

    protected int anzahl(String tabelle) {
        return Datenbank.anzahl(jdbc, tabelle);
    }

    /**
     * Startet beide Aufrufe moeglichst gleichzeitig (Startsignal per Latch) und sammelt je Aufruf Ergebnis oder Fehler,
     * in der Reihenfolge der Parameter. Haengt ein Aufruf laenger als 30 Sekunden, schlaegt der Test fehl.
     */
    protected <T> List<Ausgang<T>> parallel(Callable<T> erster, Callable<T> zweiter) throws InterruptedException {
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

    /** Ergebnis oder Fehler eines parallelen Aufrufs. */
    public record Ausgang<T>(T ergebnis, Throwable fehler) {
    }
}
