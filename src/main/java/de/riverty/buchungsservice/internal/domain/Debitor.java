package de.riverty.buchungsservice.internal.domain;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "debitor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Debitor extends Entitaet {

    private String vorname;
    private String nachname;
    private LocalDate geburtsdatum;
    private String plz;
    private String dublettenschluessel;

    public static Debitor anlegen(String vorname, String nachname, LocalDate geburtsdatum, String plz) {
        Debitor debitor = new Debitor();
        debitor.vorname = vorname;
        debitor.nachname = nachname;
        debitor.geburtsdatum = geburtsdatum;
        debitor.plz = plz;
        debitor.dublettenschluessel = Dublettenschluessel.of(vorname, nachname, geburtsdatum, plz).wert();
        return debitor;
    }
}
