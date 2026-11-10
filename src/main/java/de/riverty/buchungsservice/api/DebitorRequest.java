package de.riverty.buchungsservice.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

public record DebitorRequest(
        @NotBlank @Size(max = 100) String vorname,
        @NotBlank @Size(max = 100) String nachname,
        @NotNull @Past LocalDate geburtsdatum,
        @NotBlank @Size(max = 10) String plz) {
}
