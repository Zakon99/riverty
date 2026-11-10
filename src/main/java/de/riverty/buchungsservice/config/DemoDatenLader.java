package de.riverty.buchungsservice.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.DuplikatException;
import de.riverty.buchungsservice.internal.service.BuchungService;
import de.riverty.buchungsservice.internal.service.DebitorService;
import de.riverty.buchungsservice.internal.service.KontoService;
import de.riverty.buchungsservice.internal.service.KreditorService;
import de.riverty.buchungsservice.internal.service.SollbuchungBefehl;

/**
 * Legt im Profil {@code demo} ein Beispielkonto über die Services an. Idempotent, auch gegen einen teilweise
 * angelegten Stand: Vorhandene Stammdaten werden über die Id aus der {@link DuplikatException} weiterverwendet,
 * vorhandene Zuordnungen übersprungen, Sollbuchungen über feste clientRequestIds als Replay erkannt.
 */
@Component
@Profile("demo")
public class DemoDatenLader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDatenLader.class);
    private static final String ZUGEORDNET_VON = "demo";

    private final KreditorService kreditorService;
    private final DebitorService debitorService;
    private final KontoService kontoService;
    private final BuchungService buchungService;

    DemoDatenLader(KreditorService kreditorService, DebitorService debitorService, KontoService kontoService,
            BuchungService buchungService) {
        this.kreditorService = kreditorService;
        this.debitorService = debitorService;
        this.kontoService = kontoService;
        this.buchungService = buchungService;
    }

    @Override
    public void run(ApplicationArguments args) {
        laden();
    }

    public void laden() {
        Long kreditor = vorhandenOderNeu(() -> kreditorService.anlegen("K-DEMO", "Musterstadt Inkasso GmbH").getId());
        Long max = vorhandenOderNeu(() -> debitorService.anlegen("Max", "Mustermann", LocalDate.of(1985, 3, 14),
                "10115").getId());
        Long erika = vorhandenOderNeu(() -> debitorService.anlegen("Erika", "Mustermann", LocalDate.of(1987, 8, 22),
                "10115").getId());
        Long konto = vorhandenOderNeu(() -> kontoService.anlegen("DEMO-0001", kreditor, max).getId());
        zuordnen(konto, max);
        zuordnen(konto, erika);
        sollbuchen(konto, Buchungsart.HAUPTFORDERUNG, "1000.00", LocalDate.of(2026, 1, 15), "demo-hauptforderung");
        sollbuchen(konto, Buchungsart.GEBUEHREN, "30.00", LocalDate.of(2026, 2, 1), "demo-gebuehren");
        sollbuchen(konto, Buchungsart.ZINSEN, "50.00", LocalDate.of(2026, 3, 1), "demo-zinsen");
        log.info("Demo-Daten bereit: Konto DEMO-0001 (Id {})", konto);
    }

    private static Long vorhandenOderNeu(Supplier<Long> anlage) {
        try {
            return anlage.get();
        } catch (DuplikatException e) {
            return e.getVorhandeneId();
        }
    }

    /** Ordnet den Debitor zu, falls er es noch nicht ist (z. B. wenn das Konto ohne ihn angelegt wurde). */
    private void zuordnen(Long konto, Long debitor) {
        vorhandenOderNeu(() -> kontoService.debitorZuordnen(konto, debitor, ZUGEORDNET_VON).getId());
    }

    private void sollbuchen(Long konto, Buchungsart art, String betrag, LocalDate valuta, String clientRequestId) {
        buchungService.sollbuchen(konto, new SollbuchungBefehl(art, new BigDecimal(betrag), valuta, clientRequestId));
    }
}
