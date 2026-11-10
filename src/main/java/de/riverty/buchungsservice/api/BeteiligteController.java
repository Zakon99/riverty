package de.riverty.buchungsservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import de.riverty.buchungsservice.internal.service.DebitorService;
import de.riverty.buchungsservice.internal.service.KreditorService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/** Stammdaten der Beteiligten: Kreditoren und Debitoren. */
@RestController
@RequestMapping("/api")
class BeteiligteController {

    private final KreditorService kreditorService;
    private final DebitorService debitorService;

    BeteiligteController(KreditorService kreditorService, DebitorService debitorService) {
        this.kreditorService = kreditorService;
        this.debitorService = debitorService;
    }

    @Operation(summary = "Kreditor anlegen", description = "Kreditorennummer wird getrimmt; Duplikat → 409 mit vorhandeneId.")
    @PostMapping("/kreditoren")
    @ResponseStatus(HttpStatus.CREATED)
    KreditorResponse kreditorAnlegen(@Valid @RequestBody KreditorRequest request) {
        return KreditorResponse.von(kreditorService.anlegen(request.kreditorennummer(), request.name()));
    }

    @Operation(summary = "Debitor anlegen",
            description = "Dublette (Name, Geburtsdatum, PLZ normalisiert) → 409 mit vorhandeneId.")
    @PostMapping("/debitoren")
    @ResponseStatus(HttpStatus.CREATED)
    DebitorResponse debitorAnlegen(@Valid @RequestBody DebitorRequest request) {
        return DebitorResponse.von(debitorService.anlegen(request.vorname(), request.nachname(),
                request.geburtsdatum(), request.plz()));
    }
}
