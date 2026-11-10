package de.riverty.buchungsservice.internal.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import de.riverty.buchungsservice.internal.domain.Buchung;
import de.riverty.buchungsservice.internal.domain.Verrechnung;

public interface VerrechnungRepository extends JpaRepository<Verrechnung, Long> {

    List<Verrechnung> findBySollBuchungKontoIdOrderById(Long kontoId);

    @Query("""
            select new de.riverty.buchungsservice.internal.persistence.NettoVerrechnet(b.id, b.betrag.wert,
                sum(case when v.art = de.riverty.buchungsservice.internal.domain.VerrechnungsArt.VERRECHNUNG
                         then v.betrag.wert else -v.betrag.wert end))
            from Verrechnung v join v.sollBuchung b
            where b.konto.id = :kontoId
            group by b.id, b.betrag.wert
            """)
    List<NettoVerrechnet> nettoJeSollbuchung(Long kontoId);

    @Query("""
            select new de.riverty.buchungsservice.internal.persistence.NettoVerrechnet(b.id, b.betrag.wert,
                sum(case when v.art = de.riverty.buchungsservice.internal.domain.VerrechnungsArt.VERRECHNUNG
                         then v.betrag.wert else -v.betrag.wert end))
            from Verrechnung v join v.habenBuchung b
            where b.konto.id = :kontoId
            group by b.id, b.betrag.wert
            """)
    List<NettoVerrechnet> nettoJeHabenbuchung(Long kontoId);

    /** Nicht aufgehobene Verrechnungen, an denen die Buchung als Soll- oder Haben-Buchung beteiligt ist. */
    @Query("""
            select v from Verrechnung v join fetch v.habenBuchung h
            where v.art = de.riverty.buchungsservice.internal.domain.VerrechnungsArt.VERRECHNUNG
              and (h = :buchung or v.sollBuchung = :buchung)
              and not exists (select a from Verrechnung a where a.aufhebungVon = v)
            """)
    List<Verrechnung> findeAktive(Buchung buchung);
}
