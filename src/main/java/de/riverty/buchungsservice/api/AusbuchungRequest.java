package de.riverty.buchungsservice.api;

import de.riverty.buchungsservice.internal.domain.AusbuchungsGrund;
import de.riverty.buchungsservice.internal.service.AusbuchungBefehl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Der Betrag wird nicht übergeben: Ausgebucht wird immer der gesamte offene Rest. */
public record AusbuchungRequest(
        @NotNull AusbuchungsGrund grund,
        @Size(max = 500) String bemerkung,
        @NotBlank @Size(max = 100) String ausloeser,
        @NotBlank @Size(max = 100) String clientRequestId) {

    AusbuchungBefehl alsBefehl() {
        return new AusbuchungBefehl(grund, bemerkung, ausloeser, clientRequestId);
    }
}
