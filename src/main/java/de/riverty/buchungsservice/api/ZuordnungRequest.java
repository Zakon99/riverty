package de.riverty.buchungsservice.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ZuordnungRequest(@NotNull Long debitorId, @NotBlank @Size(max = 100) String zugeordnetVon) {
}
