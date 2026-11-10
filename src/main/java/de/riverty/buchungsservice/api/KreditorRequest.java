package de.riverty.buchungsservice.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KreditorRequest(@NotBlank @Size(max = 50) String kreditorennummer, @NotBlank @Size(max = 200) String name) {
}
