package de.riverty.buchungsservice.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "kreditor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Kreditor extends Entitaet {

    private String kreditorennummer;
    private String name;

    /** Die Kreditorennummer wird nur getrimmt, Groß-/Kleinschreibung bleibt erhalten. */
    public static Kreditor anlegen(String kreditorennummer, String name) {
        Kreditor kreditor = new Kreditor();
        kreditor.kreditorennummer = kreditorennummer.strip();
        kreditor.name = name;
        return kreditor;
    }
}
