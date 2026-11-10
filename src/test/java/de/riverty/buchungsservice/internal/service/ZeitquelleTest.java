package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.AusbuchungsGrund;
import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Buchungsart;
import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.KontoDebitor;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.StornoGrund;

class ZeitquelleTest extends ServiceTestBasis {

    @Test
    @DisplayName("S1-AK-48: buchungszeit, zugeordnetAm, Verrechnungszeitpunkt und Ausbuchungs-Valuta stammen aus der Clock")
    void alleZeitstempelAusDerClock() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Debitor d2 = testdaten.debitor("Dieter", "Zwei");

        Instant t1 = Instant.parse("2031-02-03T04:05:06Z");
        clock.setzen(t1);
        Forderungskonto konto = kontoService.anlegen("AZ-1", kr1.getId(), d1.getId());
        assertThat(zuordnungen(konto.getId())).singleElement()
                .satisfies(z -> assertThat(z.zugeordnetAm()).isEqualTo(t1));

        Instant t2 = Instant.parse("2031-03-04T05:06:07Z");
        clock.setzen(t2);
        KontoDebitor zuordnung = kontoService.debitorZuordnen(konto.getId(), d2.getId(), "sb-mueller");
        assertThat(zuordnung.getZugeordnetAm()).isEqualTo(t2);

        Instant t3 = Instant.parse("2031-04-05T06:07:08Z");
        clock.setzen(t3);
        Buchung soll = buchungService.sollbuchen(konto.getId(),
                new SollbuchungBefehl(Buchungsart.HAUPTFORDERUNG, eur("100.00"), Testdaten.VALUTA_HF, "t-soll"))
                .buchung();
        assertThat(soll.getBuchungszeit()).isEqualTo(t3);

        Instant t4 = Instant.parse("2031-05-06T07:08:09Z");
        clock.setzen(t4);
        Buchung zahlung = buchungService.zahlen(konto.getId(),
                new ZahlungBefehl(d1.getId(), eur("40.00"), Testdaten.VALUTA_ZAHLUNG, "t-zahlung")).buchung();
        assertThat(zahlung.getBuchungszeit()).isEqualTo(t4);
        assertThat(verrechnungenVon(konto.getId(), zahlung)).singleElement()
                .satisfies(v -> assertThat(v.getZeitpunkt()).isEqualTo(t4));

        Instant t5 = Instant.parse("2031-06-07T08:09:10Z");
        clock.setzen(t5);
        Buchung storno = buchungService.stornieren(konto.getId(), zahlung.getId(),
                new StornoBefehl(StornoGrund.RUECKLASTSCHRIFT, null, "sb-mueller", "t-storno")).buchung();
        assertThat(storno.getBuchungszeit()).isEqualTo(t5);
        assertThat(storno.getValuta()).isEqualTo(Testdaten.VALUTA_ZAHLUNG);
        assertThat(verrechnungenAusgeloestDurch(konto.getId(), storno)).singleElement()
                .satisfies(v -> assertThat(v.getZeitpunkt()).isEqualTo(t5));

        Instant t6 = Instant.parse("2031-07-08T09:10:11Z");
        clock.setzen(t6);
        Buchung ausbuchung = buchungService.ausbuchen(konto.getId(),
                new AusbuchungBefehl(AusbuchungsGrund.GERINGFUEGIG, null, "sb-mueller", "t-ausbuchung")).buchung();
        assertThat(ausbuchung.getBuchungszeit()).isEqualTo(t6);
        assertThat(ausbuchung.getValuta()).isEqualTo(LocalDate.of(2031, 7, 8));
        assertThat(verrechnungenVon(konto.getId(), ausbuchung)).singleElement()
                .satisfies(v -> assertThat(v.getZeitpunkt()).isEqualTo(t6));

        assertThat(buchungService.historie(konto.getId()))
                .extracting(Buchung::getBuchungszeit)
                .containsExactlyInAnyOrder(t3, t4, t5, t6);
    }
}
