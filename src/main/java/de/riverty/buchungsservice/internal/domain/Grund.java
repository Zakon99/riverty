package de.riverty.buchungsservice.internal.domain;

/** Grund eines Stornos oder einer Ausbuchung; nur die beiden fachlichen Grund-Enums sind zulässig. */
public sealed interface Grund permits StornoGrund, AusbuchungsGrund {

    String name();
}
