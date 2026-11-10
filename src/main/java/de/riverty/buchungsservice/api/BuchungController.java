package de.riverty.buchungsservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.riverty.buchungsservice.internal.service.BuchungService;
import de.riverty.buchungsservice.internal.service.BuchungsErgebnis;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/**
 * Buchungen, idempotent über die clientRequestId: 201 bei neuer Buchung, 200 mit der Originalbuchung bei einem
 * identischen Replay, 409 bei gleicher clientRequestId mit anderem Inhalt.
 */
@RestController
@RequestMapping("/api/konten/{kontoId}")
class BuchungController {

    private final BuchungService buchungService;

    BuchungController(BuchungService buchungService) {
        this.buchungService = buchungService;
    }

    @Operation(summary = "Sollbuchung", description = "Hauptforderung, Zinsen oder Gebühren; Betrag wird HALF_UP auf "
            + "zwei Nachkommastellen gerundet.")
    @PostMapping("/sollbuchungen")
    ResponseEntity<BuchungResponse> sollbuchen(@PathVariable Long kontoId,
            @Valid @RequestBody SollbuchungRequest request) {
        return antwort(buchungService.sollbuchen(kontoId, request.alsBefehl()));
    }

    @Operation(summary = "Zahlung", description = "Wird sofort nach § 367 BGB verrechnet; ein Rest bleibt Guthaben "
            + "des zahlenden Debitors.")
    @PostMapping("/zahlungen")
    ResponseEntity<BuchungResponse> zahlen(@PathVariable Long kontoId, @Valid @RequestBody ZahlungRequest request) {
        return antwort(buchungService.zahlen(kontoId, request.alsBefehl()));
    }

    @Operation(summary = "Storno", description = "Gegenbuchung mit Betrag und Valuta des Originals; hebt dessen "
            + "Verrechnungen auf.")
    @PostMapping("/buchungen/{buchungId}/storno")
    ResponseEntity<BuchungResponse> stornieren(@PathVariable Long kontoId, @PathVariable Long buchungId,
            @Valid @RequestBody StornoRequest request) {
        return antwort(buchungService.stornieren(kontoId, buchungId, request.alsBefehl()));
    }

    @Operation(summary = "Ausbuchung", description = "Bucht den gesamten offenen Rest aus; Valuta = heute (UTC).")
    @PostMapping("/ausbuchung")
    ResponseEntity<BuchungResponse> ausbuchen(@PathVariable Long kontoId, @Valid @RequestBody AusbuchungRequest request) {
        return antwort(buchungService.ausbuchen(kontoId, request.alsBefehl()));
    }

    private static ResponseEntity<BuchungResponse> antwort(BuchungsErgebnis ergebnis) {
        HttpStatus status = ergebnis.neuAngelegt() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(BuchungResponse.von(ergebnis.buchung()));
    }
}
