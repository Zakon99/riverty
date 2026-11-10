package de.riverty.buchungsservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import de.riverty.buchungsservice.internal.domain.KonfliktException;
import de.riverty.buchungsservice.internal.service.BuchungService;
import de.riverty.buchungsservice.internal.service.SollbuchungBefehl;

/**
 * Abbildung technischer Konflikte und unerwarteter Fehler auf ProblemDetail. Eigener Kontext, weil der
 * {@link BuchungService} gemockt ist; Konto und Kreditor entstehen ueber die echten Services.
 */
class FehlerabbildungMitGemocktemServiceTest extends ApiTestBasis {

    @MockitoBean
    private BuchungService buchungService;

    private long kontoId;

    @BeforeEach
    void kontoBereitstellen() throws Exception {
        kontoId = kontoAnlegen("AZ-1", kreditorAnlegen("K-1001"), null);
    }

    private ResultActions sollbuchen() throws Exception {
        return postJson(sollbuchungen(kontoId), sollJson("ZINSEN", "10.00", "2026-02-01", "r-1"));
    }

    static Stream<Arguments> konflikte() {
        return Stream.of(
                Arguments.of(new KonfliktException("Konflikt mit dem aktuellen Zustand")),
                Arguments.of(new ObjectOptimisticLockingFailureException("Forderungskonto", 1L)),
                Arguments.of(new CannotAcquireLockException("Sperre nicht erhalten")),
                Arguments.of(new PessimisticLockingFailureException("Sperre abgelaufen")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("konflikte")
    @DisplayName("S2-AK-32: KonfliktException, OptimisticLock und andere ConcurrencyFailureException → 409 konflikt")
    void konfliktAbbildung(RuntimeException fehler) throws Exception {
        when(buchungService.sollbuchen(any(Long.class), any(SollbuchungBefehl.class))).thenThrow(fehler);

        assertProblem(sollbuchen(), 409, "konflikt");
    }

    @Test
    @DisplayName("S2-AK-33 / S2-AK-43: Unerwarteter Fehler → 500 als ProblemDetail (type urn:buchungsservice:intern) ohne Stacktrace und Klassennamen")
    void unerwarteterFehler() throws Exception {
        when(buchungService.sollbuchen(any(Long.class), any(SollbuchungBefehl.class)))
                .thenThrow(new IllegalStateException("interner Zustand kaputt"));

        ResultActions antwort = sollbuchen()
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:buchungsservice:intern"))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").isString())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());

        assertThat(text(antwort))
                .doesNotContain("IllegalStateException")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("de.riverty")
                .doesNotContain("\tat ");
    }
}
