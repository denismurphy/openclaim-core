package com.openclaim.engine.claim;

/** A code from a code system, mirroring FHIR {@code Coding} without depending on HAPI. */
public record Coding(String system, String code) {}
