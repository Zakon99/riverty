package de.riverty.buchungsservice.api;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import de.riverty.buchungsservice.internal.domain.DuplikatException;
import de.riverty.buchungsservice.internal.domain.FachlicheRegelVerletztException;
import de.riverty.buchungsservice.internal.domain.KonfliktException;
import de.riverty.buchungsservice.internal.domain.NichtGefundenException;
import de.riverty.buchungsservice.internal.domain.UngueltigeSucheException;
import de.riverty.buchungsservice.internal.domain.UngueltigerBetragException;

/**
 * Bildet alle Fehler einheitlich auf ProblemDetail (RFC 9457) ab. Auch die Standardfehler von Spring MVC (unlesbares
 * JSON, falscher Typ einer Pfadvariable, Bean Validation, unbekannte Ressource ...) laufen über
 * {@link #handleExceptionInternal}, das {@code type}, {@code title}, {@code instance} und ggf. {@code feldfehler} setzt.
 */
@RestControllerAdvice
class FehlerBehandlung extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FehlerBehandlung.class);
    private static final URI OHNE_TYP = URI.create("about:blank");

    @ExceptionHandler(NichtGefundenException.class)
    ResponseEntity<Object> nichtGefunden(NichtGefundenException ex, WebRequest request) {
        return antwort(ex, HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(DuplikatException.class)
    ResponseEntity<Object> duplikat(DuplikatException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        Fehlerart.DUPLIKAT.kennzeichnen(problem);
        problem.setProperty("vorhandeneId", ex.getVorhandeneId());
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(KonfliktException.class)
    ResponseEntity<Object> konflikt(KonfliktException ex, WebRequest request) {
        return antwort(ex, HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** Optimistic/Pessimistic Locking und Lock-Timeouts; die technische Meldung wird nicht nach außen gegeben. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<Object> paralleleAenderung(ConcurrencyFailureException ex, WebRequest request) {
        log.info("Parallele Änderung bei {}: {}", fuerLog(request), ex.getMessage());
        return antwort(ex, HttpStatus.CONFLICT, "Das Konto wurde parallel geändert. Bitte die Anfrage wiederholen.",
                request);
    }

    @ExceptionHandler(FachlicheRegelVerletztException.class)
    ResponseEntity<Object> fachregel(FachlicheRegelVerletztException ex, WebRequest request) {
        return antwort(ex, HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), request);
    }

    @ExceptionHandler({ UngueltigerBetragException.class, UngueltigeSucheException.class })
    ResponseEntity<Object> ungueltig(RuntimeException ex, WebRequest request) {
        return antwort(ex, HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Unerwartete Fehler: vollständig loggen, nach außen nur eine neutrale Meldung ohne technische Details. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unerwartet(Exception ex, WebRequest request) {
        log.error("Unerwarteter Fehler bei {}", fuerLog(request), ex);
        return antwort(ex, HttpStatus.INTERNAL_SERVER_ERROR, "Ein unerwarteter Fehler ist aufgetreten.", request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail vorhanden ? vorhanden : ProblemDetail.forStatus(statusCode);
        if (problem.getType() == null || OHNE_TYP.equals(problem.getType())) {
            Fehlerart.fuer(statusCode).kennzeichnen(problem);
        }
        List<Feldfehler> feldfehler = feldfehler(ex);
        if (!feldfehler.isEmpty()) {
            problem.setProperty("feldfehler", feldfehler);
        }
        if (request instanceof ServletWebRequest servlet) {
            problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    private ResponseEntity<Object> antwort(Exception ex, HttpStatus status, String detail, WebRequest request) {
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail), new HttpHeaders(), status,
                request);
    }

    /** Request-Beschreibung für das Log ohne Zeilenumbrüche, damit kein Client gefälschte Logzeilen einschleusen kann. */
    private static String fuerLog(WebRequest request) {
        return request.getDescription(false).replaceAll("[\r\n]", "_");
    }

    private static List<Feldfehler> feldfehler(Exception ex) {
        return switch (ex) {
            case MethodArgumentNotValidException e -> e.getBindingResult().getFieldErrors().stream()
                    .map(fehler -> new Feldfehler(fehler.getField(), fehler.getDefaultMessage()))
                    .toList();
            case HandlerMethodValidationException e -> e.getParameterValidationResults().stream()
                    .flatMap(ergebnis -> ergebnis.getResolvableErrors().stream()
                            .map(fehler -> new Feldfehler(ergebnis.getMethodParameter().getParameterName(),
                                    fehler.getDefaultMessage())))
                    .toList();
            case UngueltigerBetragException e -> List.of(new Feldfehler("betrag", e.getMessage()));
            default -> List.of();
        };
    }
}
