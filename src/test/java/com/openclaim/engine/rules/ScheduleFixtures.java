package com.openclaim.engine.rules;

import static com.openclaim.engine.claim.SubmissionFixtures.DIAGNOSIS_SYSTEM;
import static com.openclaim.engine.claim.SubmissionFixtures.PROCEDURE_SYSTEM;

import com.openclaim.engine.claim.Coding;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Builder for fee schedule snapshots; the default admits the claim built by {@code SubmissionFixtures}. */
public final class ScheduleFixtures {

    private final Map<Coding, FeeScheduleSnapshot.ProcedureRules> procedures = new HashMap<>();
    private final Set<FeeScheduleSnapshot.CodePair> conflicts = new HashSet<>();
    private boolean payerKnown = true;

    private ScheduleFixtures() {}

    public static ScheduleFixtures aSchedule() {
        return new ScheduleFixtures().procedure("PROC-SPIRO", Set.of("DX-ASTHMA", "DX-COPD"), Set.of("AMB", "IMP"));
    }

    public static Coding procedure(String code) {
        return new Coding(PROCEDURE_SYSTEM, code);
    }

    public static Coding diagnosis(String code) {
        return new Coding(DIAGNOSIS_SYSTEM, code);
    }

    public ScheduleFixtures procedure(String code, Set<String> diagnoses, Set<String> settings) {
        procedures.put(
                procedure(code),
                new FeeScheduleSnapshot.ProcedureRules(
                        diagnoses.stream()
                                .map(ScheduleFixtures::diagnosis)
                                .collect(java.util.stream.Collectors.toSet()),
                        settings));
        return this;
    }

    public ScheduleFixtures conflict(String a, String b) {
        conflicts.add(new FeeScheduleSnapshot.CodePair(procedure(a), procedure(b)));
        conflicts.add(new FeeScheduleSnapshot.CodePair(procedure(b), procedure(a)));
        return this;
    }

    public ScheduleFixtures unknownPayer() {
        this.payerKnown = false;
        return this;
    }

    public FeeScheduleSnapshot build() {
        return new FeeScheduleSnapshot(procedures, conflicts, payerKnown);
    }
}
