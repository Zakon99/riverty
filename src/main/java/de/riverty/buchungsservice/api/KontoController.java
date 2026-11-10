package de.riverty.buchungsservice.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import de.riverty.buchungsservice.internal.service.KonsistenzService;
import de.riverty.buchungsservice.internal.service.KontoService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/** Forderungskonten: Anlage, Debitor-Zuordnung, Suche, Kontoansicht und Konsistenzprüfung. */
@RestController
@RequestMapping("/api/konten")
class KontoController {

    private final KontoService kontoService;
    private final KonsistenzService konsistenzService;

    KontoController(KontoService kontoService, KonsistenzService konsistenzService) {
        this.kontoService = kontoService;
        this.konsistenzService = konsistenzService;
    }

    @Operation(summary = "Forderungskonto anlegen", description = "Optional mit erstem Debitor (zugeordnetVon = Kontoanlage).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    KontoResponse anlegen(@Valid @RequestBody KontoRequest request) {
        return KontoResponse.von(
                kontoService.anlegenMitUebersicht(request.aktenzeichen(), request.kreditorId(), request.debitorId()));
    }

    @Operation(summary = "Debitor dem Konto zuordnen", description = "Bereits zugeordnet → 409 mit vorhandeneId = Debitor-Id.")
    @PostMapping("/{kontoId}/debitoren")
    @ResponseStatus(HttpStatus.CREATED)
    ZuordnungResponse debitorZuordnen(@PathVariable Long kontoId, @Valid @RequestBody ZuordnungRequest request) {
        return ZuordnungResponse.von(kontoService.debitorZuordnen(kontoId, request.debitorId(), request.zugeordnetVon()));
    }

    @Operation(summary = "Konten suchen",
            description = "Nach Aktenzeichen und/oder Kreditorennummer: getrimmt, exakt, UND-verknüpft, nach Id sortiert. "
                    + "Mindestens ein Parameter ist Pflicht.")
    @GetMapping
    List<KontoResponse> suchen(@RequestParam(required = false) String aktenzeichen,
            @RequestParam(required = false) String kreditorennummer) {
        return kontoService.suchen(aktenzeichen, kreditorennummer).stream().map(KontoResponse::von).toList();
    }

    @Operation(summary = "Kontoansicht", description = "Saldo, Kreditor, Debitoren mit Guthaben und Historie "
            + "(Valuta, Buchungszeit, Id aufsteigend).")
    @GetMapping("/{kontoId}")
    KontoDetailResponse ansicht(@PathVariable Long kontoId) {
        return KontoDetailResponse.von(kontoService.ansicht(kontoId));
    }

    @Operation(summary = "Konsistenz prüfen", description = "Saldo gegen Buchungen und Verrechnungen gegen Beträge.")
    @GetMapping("/{kontoId}/konsistenz")
    KonsistenzResponse konsistenz(@PathVariable Long kontoId) {
        return KonsistenzResponse.von(konsistenzService.pruefen(kontoId));
    }
}
