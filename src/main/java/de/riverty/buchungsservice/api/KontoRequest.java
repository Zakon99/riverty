package de.riverty.buchungsservice.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code debitorId} ist optional; ist sie gesetzt, wird der Debitor bei der Anlage zugeordnet. */
public record KontoRequest(@NotBlank @Size(max = 50) String aktenzeichen, @NotNull Long kreditorId, Long debitorId) {
}
