package com.openclaim.engine.claim;

import org.springframework.data.relational.core.mapping.Table;

@Table("claim_diagnosis")
public record ClaimDiagnosis(String system, String code) {}
