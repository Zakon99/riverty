package de.riverty.buchungsservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.riverty.buchungsservice.internal.domain.Debitor;
import de.riverty.buchungsservice.internal.domain.DuplikatException;
import de.riverty.buchungsservice.internal.domain.Forderungskonto;
import de.riverty.buchungsservice.internal.domain.KontoDebitor;
import de.riverty.buchungsservice.internal.domain.Kreditor;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;

class KontoServiceTest extends ServiceTestBasis {

    @Test
    @DisplayName("S1-AK-09: Konto ohne und mit Debitor anlegen → Saldo 0.00, Kreditor, Zuordnung mit Clock-Zeit")
    void kontoAnlegen() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Instant jetzt = clock.instant();

        Forderungskonto az1 = kontoService.anlegen("AZ-1", kr1.getId(), null);
        Forderungskonto az2 = kontoService.anlegen("AZ-2", kr1.getId(), d1.getId());

        assertThat(az1.getId()).isNotNull();
        assertThat(az1.getAktenzeichen()).isEqualTo("AZ-1");
        assertThat(az1.getSaldo()).isEqualByComparingTo("0.00");
        assertThat(az1.getKreditor().getId()).isEqualTo(kr1.getId());
        assertThat(zuordnungen(az1.getId())).isEmpty();

        assertThat(az2.getSaldo()).isEqualByComparingTo("0.00");
        assertThat(az2.getKreditor().getId()).isEqualTo(kr1.getId());
        assertThat(zuordnungen(az2.getId()))
                .containsExactly(new Zuordnung(d1.getId(), jetzt, "Kontoanlage"));
    }

    @Test
    @DisplayName("S1-AK-10: Zweites Konto mit gleichem (getrimmtem) Aktenzeichen → Duplikat; case-sensitive")
    void aktenzeichenDuplikat() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Forderungskonto vorhanden = kontoService.anlegen("AZ-1", kr1.getId(), null);

        assertThatThrownBy(() -> kontoService.anlegen("AZ-1", kr1.getId(), null))
                .isInstanceOfSatisfying(DuplikatException.class,
                        e -> assertThat(e.getVorhandeneId()).isEqualTo(vorhanden.getId()));
        assertThatThrownBy(() -> kontoService.anlegen(" AZ-1 ", kr1.getId(), null))
                .isInstanceOfSatisfying(DuplikatException.class,
                        e -> assertThat(e.getVorhandeneId()).isEqualTo(vorhanden.getId()));
        assertThat(anzahl("forderungskonto")).isEqualTo(1);

        Forderungskonto klein = kontoService.anlegen("az-1", kr1.getId(), null);

        assertThat(klein.getId()).isNotEqualTo(vorhanden.getId());
        assertThat(klein.getAktenzeichen()).isEqualTo("az-1");
        assertThat(anzahl("forderungskonto")).isEqualTo(2);
    }

    @Test
    @DisplayName("S1-AK-10: Aktenzeichen wird getrimmt gespeichert")
    void aktenzeichenGetrimmt() {
        Kreditor kr1 = testdaten.kreditor("KR-1");

        Forderungskonto konto = kontoService.anlegen("  AZ-7 ", kr1.getId(), null);

        assertThat(konto.getAktenzeichen()).isEqualTo("AZ-7");
    }

    @Test
    @DisplayName("S1-AK-11: Debitor nachtraeglich zuordnen → zugeordnetAm aus Clock, zugeordnetVon gespeichert; n:m")
    void debitorNachtraeglichZuordnen() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Debitor d2 = testdaten.debitor("Dieter", "Zwei");
        Forderungskonto az1 = kontoService.anlegen("AZ-1", kr1.getId(), d1.getId());
        Forderungskonto az2 = kontoService.anlegen("AZ-2", kr1.getId(), null);
        clock.vorstellen(Duration.ofMinutes(5));
        Instant jetzt = clock.instant();

        KontoDebitor zuordnung = kontoService.debitorZuordnen(az1.getId(), d2.getId(), "sb-mueller");

        assertThat(zuordnung.getId()).isNotNull();
        assertThat(zuordnung.getDebitor().getId()).isEqualTo(d2.getId());
        assertThat(zuordnung.getKonto().getId()).isEqualTo(az1.getId());
        assertThat(zuordnung.getZugeordnetAm()).isEqualTo(jetzt);
        assertThat(zuordnung.getZugeordnetVon()).isEqualTo("sb-mueller");
        assertThat(zuordnungen(az1.getId())).extracting(Zuordnung::debitorId)
                .containsExactlyInAnyOrder(d1.getId(), d2.getId());

        kontoService.debitorZuordnen(az2.getId(), d2.getId(), "sb-mueller");

        assertThat(zuordnungen(az2.getId())).extracting(Zuordnung::debitorId).containsExactly(d2.getId());
    }

    @Test
    @DisplayName("S1-AK-11: Erneute Zuordnung desselben Debitors zum selben Konto → Duplikat")
    void doppelteZuordnung() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Forderungskonto az1 = kontoService.anlegen("AZ-1", kr1.getId(), d1.getId());
        int vorher = anzahl("konto_debitor");

        assertThatThrownBy(() -> kontoService.debitorZuordnen(az1.getId(), d1.getId(), "sb-mueller"))
                .isInstanceOf(DuplikatException.class);
        assertThat(anzahl("konto_debitor")).isEqualTo(vorher);
    }

    @Test
    @DisplayName("S1-AK-11: Kreditor des Kontos ist nicht aenderbar (keine Operation dafuer)")
    void kreditorNichtAenderbar() {
        assertThat(Arrays.stream(Forderungskonto.class.getMethods()).map(m -> m.getName()))
                .noneMatch(name -> name.equals("setKreditor"));
        assertThat(Arrays.stream(KontoService.class.getMethods()).map(m -> m.getName()))
                .noneMatch(name -> name.toLowerCase().contains("kreditor"));
    }

    @Test
    @DisplayName("S1-AK-12: Unbekannter Kreditor/Debitor bei Anlage → NichtGefunden, kein Konto angelegt")
    void anlageMitUnbekanntenIds() {
        Kreditor kr1 = testdaten.kreditor("KR-1");

        assertThatThrownBy(() -> kontoService.anlegen("AZ-9", UNBEKANNT, null))
                .isInstanceOf(NichtGefundenException.class);
        assertThatThrownBy(() -> kontoService.anlegen("AZ-9", kr1.getId(), UNBEKANNT))
                .isInstanceOf(NichtGefundenException.class);
        assertThat(anzahl("forderungskonto")).isZero();
        assertThat(anzahl("konto_debitor")).isZero();
    }

    @Test
    @DisplayName("S1-AK-12: Zuordnung mit unbekanntem Debitor/Konto bzw. Laden unbekanntes Konto → NichtGefunden")
    void zuordnungMitUnbekanntenIds() {
        Kreditor kr1 = testdaten.kreditor("KR-1");
        Debitor d1 = testdaten.debitor("Dora", "Eins");
        Forderungskonto az1 = kontoService.anlegen("AZ-1", kr1.getId(), null);

        assertThatThrownBy(() -> kontoService.debitorZuordnen(az1.getId(), UNBEKANNT, "sb-mueller"))
                .isInstanceOf(NichtGefundenException.class);
        assertThatThrownBy(() -> kontoService.debitorZuordnen(UNBEKANNT, d1.getId(), "sb-mueller"))
                .isInstanceOf(NichtGefundenException.class);
        assertThatThrownBy(() -> kontoService.laden(UNBEKANNT))
                .isInstanceOf(NichtGefundenException.class);
        assertThat(anzahl("konto_debitor")).isZero();
    }
}
