package de.riverty.buchungsservice.api;

import de.riverty.buchungsservice.internal.domain.StornoGrund;
import de.riverty.buchungsservice.internal.service.StornoBefehl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StornoRequest(
        @NotNull StornoGrund grund,
        @Size(max = 500) String bemerkung,
        @NotBlank @Size(max = 100) String ausloeser,
        @NotBlank @Size(max = 100) String clientRequestId) {

    StornoBefehl alsBefehl() {
        return new StornoBefehl(grund, bemerkung, ausloeser, clientRequestId);
    }
}
