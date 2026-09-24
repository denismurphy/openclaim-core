package com.openclaim.engine.rules;

import com.openclaim.engine.claim.Coding;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The reference data one claim is checked against, loaded in a single round trip before the rules run.
 *
 * @param procedures entries for the procedure codes on the claim that exist in the fee schedule
 * @param conflicts pairs of procedures that must not be billed together; each pair is stored in both orders
 * @param payerKnown whether the claim's payer code is a registered payer
 */
public record FeeScheduleSnapshot(Map<Coding, ProcedureRules> procedures, Set<CodePair> conflicts, boolean payerKnown) {

    public FeeScheduleSnapshot {
        procedures = Map.copyOf(procedures);
        conflicts = Set.copyOf(conflicts);
    }

    /**
     * @param allowedDiagnoses diagnoses that clinically indicate the procedure
     * @param allowedSettings v3-ActCode encounter classes the procedure may be billed in
     */
    public record ProcedureRules(Set<Coding> allowedDiagnoses, Set<String> allowedSettings) {

        public ProcedureRules {
            allowedDiagnoses = Set.copyOf(allowedDiagnoses);
            allowedSettings = Set.copyOf(allowedSettings);
        }
    }

    public record CodePair(Coding first, Coding second) {}

    public Optional<ProcedureRules> rulesFor(Coding procedure) {
        return Optional.ofNullable(procedures.get(procedure));
    }

    public boolean conflicting(Coding a, Coding b) {
        return conflicts.contains(new CodePair(a, b));
    }
}
