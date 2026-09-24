package com.openclaim.engine.rules;

import com.openclaim.engine.claim.Coding;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Loads a claim's fee schedule snapshot with one indexed query per table. The procedure list is bounded by
 * {@code openclaim.fhir.max-items}.
 */
@Repository
class JdbcFeeScheduleRepository implements FeeScheduleRepository {

    private final JdbcClient jdbc;

    JdbcFeeScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FeeScheduleSnapshot snapshotFor(Collection<Coding> procedures, @Nullable String payerCode) {
        boolean payerKnown = payerCode != null
                && jdbc.sql("SELECT EXISTS (SELECT 1 FROM payer WHERE code = :code)")
                        .param("code", payerCode)
                        .query(Boolean.class)
                        .single();
        if (procedures.isEmpty()) {
            return new FeeScheduleSnapshot(Map.of(), Set.of(), payerKnown);
        }
        List<Object[]> keys = procedures.stream()
                .map(p -> new Object[] {p.system(), p.code()})
                .toList();

        Map<Coding, Set<Coding>> diagnoses = new HashMap<>();
        Map<Coding, Set<String>> settings = new HashMap<>();
        jdbc.sql("SELECT system, code FROM fee_schedule_procedure WHERE (system, code) IN (:keys)")
                .param("keys", keys)
                .query((rs, row) -> new Coding(rs.getString("system"), rs.getString("code")))
                .list()
                .forEach(known -> {
                    diagnoses.put(known, new HashSet<>());
                    settings.put(known, new HashSet<>());
                });
        jdbc.sql("""
                        SELECT procedure_system, procedure_code, diagnosis_system, diagnosis_code
                        FROM fee_schedule_indication
                        WHERE (procedure_system, procedure_code) IN (:keys)""")
                .param("keys", keys)
                .query((rs, row) -> {
                    diagnoses
                            .get(new Coding(rs.getString("procedure_system"), rs.getString("procedure_code")))
                            .add(new Coding(rs.getString("diagnosis_system"), rs.getString("diagnosis_code")));
                    return row;
                })
                .list();
        jdbc.sql("""
                        SELECT procedure_system, procedure_code, encounter_class
                        FROM fee_schedule_setting
                        WHERE (procedure_system, procedure_code) IN (:keys)""")
                .param("keys", keys)
                .query((rs, row) -> {
                    settings.get(new Coding(rs.getString("procedure_system"), rs.getString("procedure_code")))
                            .add(rs.getString("encounter_class"));
                    return row;
                })
                .list();
        Set<FeeScheduleSnapshot.CodePair> conflicts = new HashSet<>();
        jdbc.sql("""
                        SELECT first_system, first_code, second_system, second_code
                        FROM fee_schedule_conflict
                        WHERE (first_system, first_code) IN (:keys) AND (second_system, second_code) IN (:keys)""")
                .param("keys", keys)
                .query((rs, row) -> {
                    Coding first = new Coding(rs.getString("first_system"), rs.getString("first_code"));
                    Coding second = new Coding(rs.getString("second_system"), rs.getString("second_code"));
                    conflicts.add(new FeeScheduleSnapshot.CodePair(first, second));
                    conflicts.add(new FeeScheduleSnapshot.CodePair(second, first));
                    return row;
                })
                .list();

        Map<Coding, FeeScheduleSnapshot.ProcedureRules> rules = new HashMap<>();
        diagnoses.forEach((procedure, allowed) ->
                rules.put(procedure, new FeeScheduleSnapshot.ProcedureRules(allowed, settings.get(procedure))));
        return new FeeScheduleSnapshot(rules, conflicts, payerKnown);
    }
}
