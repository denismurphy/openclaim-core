package com.openclaim.engine.claim;

import org.springframework.data.relational.core.mapping.Table;

@Table("claim_line")
public record ClaimLine(int sequence, String procedureSystem, String procedureCode, long netMinor) {}
