package de.riverty.buchungsservice.architektur;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Map;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * S2-AK-37/38: Paketabhaengigkeiten und keine anonymen Maps als Fach-/API-Typen.
 * ArchUnit wertet {@code @DisplayName} nicht aus; die Kriterien-Nummer steht daher im Feldnamen und im {@code because}.
 */
@AnalyzeClasses(packages = "de.riverty.buchungsservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitekturTest {

    private static final String BASIS = "de.riverty.buchungsservice";
    private static final String API = BASIS + ".api..";
    private static final String INTERNAL = BASIS + ".internal..";
    private static final String DOMAIN = BASIS + ".internal.domain..";
    private static final String SERVICE = BASIS + ".internal.service..";
    private static final String PERSISTENCE = BASIS + ".internal.persistence..";

    // ---------------------------------------------------------------- S2-AK-37

    @ArchTest
    static final ArchRule S2_AK_37_INTERNAL_KENNT_API_NICHT = noClasses()
            .that().resideInAPackage(INTERNAL)
            .should().dependOnClassesThat().resideInAPackage(API)
            .because("S2-AK-37: internal haengt nicht von api ab");

    @ArchTest
    static final ArchRule S2_AK_37_API_GREIFT_NICHT_AUF_PERSISTENCE_ZU = noClasses()
            .that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAPackage(PERSISTENCE)
            .because("S2-AK-37: Controller greifen nicht auf Repositories zu");

    @ArchTest
    static final ArchRule S2_AK_37_PERSISTENCE_KENNT_SERVICE_NICHT = noClasses()
            .that().resideInAPackage(PERSISTENCE)
            .should().dependOnClassesThat().resideInAPackage(SERVICE)
            .because("S2-AK-37: persistence haengt nicht von service ab");

    @ArchTest
    static final ArchRule S2_AK_37_DOMAIN_KENNT_SERVICE_UND_PERSISTENCE_NICHT = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, PERSISTENCE)
            .because("S2-AK-37: domain haengt weder von service noch von persistence ab");

    // ---------------------------------------------------------------- S2-AK-38

    /**
     * Felder in api und domain duerfen nie Maps sein; in service nur private Felder von Nicht-Record-Klassen
     * (Implementierungsdetail). Record-Komponenten sind immer verboten.
     */
    @ArchTest
    static final ArchRule S2_AK_38_KEINE_MAP_FELDER = fields()
            .that().areDeclaredInClassesThat().resideInAnyPackage(API, DOMAIN, SERVICE)
            .and(new DescribedPredicate<JavaField>("nicht als privates Implementierungsdetail einer Service-Klasse") {
                @Override
                public boolean test(JavaField feld) {
                    boolean service = feld.getOwner().getPackageName().startsWith(BASIS + ".internal.service");
                    boolean privat = feld.getModifiers().contains(JavaModifier.PRIVATE);
                    return !(service && privat && !feld.getOwner().isRecord());
                }
            })
            .and(new DescribedPredicate<JavaField>("nicht synthetisch") {
                @Override
                public boolean test(JavaField feld) {
                    return !feld.getModifiers().contains(JavaModifier.SYNTHETIC);
                }
            })
            .should(new ArchCondition<JavaField>("keinen Map-Typ haben") {
                @Override
                public void check(JavaField feld, ConditionEvents events) {
                    if (enthaeltMap(feld.getType())) {
                        events.add(SimpleConditionEvent.violated(feld,
                                "Feld " + feld.getFullName() + " hat den Typ " + feld.getType().getName()));
                    }
                }
            })
            .allowEmptyShould(true)
            .because("S2-AK-38: keine anonymen Maps als Fach-/API-Typen (Felder, Record-Komponenten)");

    /** Oeffentliche Methoden und Konstruktoren in api/domain/service: kein Map-Parameter, kein Map-Rueckgabetyp. */
    @ArchTest
    static final ArchRule S2_AK_38_KEINE_MAPS_IN_OEFFENTLICHEN_SIGNATUREN = codeUnits()
            .that().areDeclaredInClassesThat().resideInAnyPackage(API, DOMAIN, SERVICE)
            .and().arePublic()
            .should(new ArchCondition<JavaCodeUnit>("keine Map in Parametern oder Rueckgabetyp haben") {
                @Override
                public void check(JavaCodeUnit methode, ConditionEvents events) {
                    if (enthaeltMap(methode.getReturnType())) {
                        events.add(SimpleConditionEvent.violated(methode,
                                methode.getFullName() + " liefert " + methode.getReturnType().getName()));
                    }
                    for (JavaType parameter : methode.getParameterTypes()) {
                        if (enthaeltMap(parameter)) {
                            events.add(SimpleConditionEvent.violated(methode,
                                    methode.getFullName() + " hat den Parameter " + parameter.getName()));
                        }
                    }
                }
            })
            .allowEmptyShould(true)
            .because("S2-AK-38: keine anonymen Maps als Fach-/API-Typen (oeffentliche Signaturen)");

    /** Map oder Subtyp, auch als Typargument (z. B. {@code List<Map<String, Object>>}). */
    private static boolean enthaeltMap(JavaType typ) {
        Set<JavaClass> beteiligte = typ.getAllInvolvedRawTypes();
        return beteiligte.stream().anyMatch(klasse -> klasse.isAssignableTo(Map.class));
    }
}
