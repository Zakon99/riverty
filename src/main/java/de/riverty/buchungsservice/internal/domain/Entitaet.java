package de.riverty.buchungsservice.internal.domain;

import org.hibernate.proxy.HibernateProxy;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * Gemeinsame Basis aller Entitäten: datenbankvergebene Id, Gleichheit ausschließlich über die Id.
 * Hibernate-Proxies werden über ihre Entitätsklasse verglichen, ohne sie zu initialisieren.
 */
@MappedSuperclass
@Getter
public abstract class Entitaet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public boolean equals(Object anderes) {
        if (this == anderes) {
            return true;
        }
        if (anderes == null || entitaetsklasse(this) != entitaetsklasse(anderes)) {
            return false;
        }
        Long eigeneId = getId();
        return eigeneId != null && eigeneId.equals(((Entitaet) anderes).getId());
    }

    @Override
    public int hashCode() {
        return entitaetsklasse(this).hashCode();
    }

    private static Class<?> entitaetsklasse(Object objekt) {
        return objekt instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : objekt.getClass();
    }
}
