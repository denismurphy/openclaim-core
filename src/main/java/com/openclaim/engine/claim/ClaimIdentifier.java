package com.openclaim.engine.claim;

/** The submitter's business identifier for a claim ({@code Claim.identifier}); unique per system. */
public record ClaimIdentifier(String system, String value) {}
