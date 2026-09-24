package com.openclaim.engine.rules.validators;

/** FHIRPath expressions pointing violations at the element of the submitted Claim they concern. */
final class Expressions {

    static final String V3_ACT_CODE = "http://terminology.hl7.org/CodeSystem/v3-ActCode";

    private Expressions() {}

    static String procedureOf(int itemSequence) {
        return "Claim.item.where(sequence = %d).productOrService".formatted(itemSequence);
    }
}
