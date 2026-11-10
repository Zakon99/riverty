package de.riverty.buchungsservice.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.riverty.buchungsservice.internal.service.VerrechnungService;
import io.swagger.v3.oas.annotations.Operation;

/** Offene Posten und Verrechnungen nach § 367 BGB. */
@RestController
@RequestMapping("/api/konten/{kontoId}")
class VerrechnungController {

    private final VerrechnungService verrechnungService;

    VerrechnungController(VerrechnungService verrechnungService) {
        this.verrechnungService = verrechnungService;
    }

    @Operation(summary = "Offene Posten", description = "In der Reihenfolge, in der sie bedient werden "
            + "(Gebühren, Zinsen, Hauptforderung; je Art älteste Valuta zuerst).")
    @GetMapping("/offene-posten")
    List<OffenerPostenResponse> offenePosten(@PathVariable Long kontoId) {
        return verrechnungService.offenePosten(kontoId).stream().map(OffenerPostenResponse::von).toList();
    }

    @Operation(summary = "Verrechnungen", description = "Alle Verrechnungen und Aufhebungen des Kontos, nach Id.")
    @GetMapping("/verrechnungen")
    List<VerrechnungResponse> verrechnungen(@PathVariable Long kontoId) {
        return verrechnungService.verrechnungen(kontoId).stream().map(VerrechnungResponse::von).toList();
    }

    @Operation(summary = "Guthaben verrechnen", description = "Verteilt vorhandenes Guthaben auf offene Posten; "
            + "liefert die neu erzeugten Verrechnungen (ohne Wirkung: leere Liste).")
    @PostMapping("/verrechnung")
    List<VerrechnungResponse> verrechnen(@PathVariable Long kontoId) {
        return verrechnungService.verrechnen(kontoId).stream().map(VerrechnungResponse::von).toList();
    }
}
