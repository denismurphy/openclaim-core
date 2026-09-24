package com.openclaim.engine.rules;

import static com.openclaim.engine.rules.ScheduleFixtures.diagnosis;
import static com.openclaim.engine.rules.ScheduleFixtures.procedure;
import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;

@DataJdbcTest
@Import({TestcontainersConfiguration.class, JdbcFeeScheduleRepository.class})
class JdbcFeeScheduleRepositoryIT {

    @Autowired
    JdbcFeeScheduleRepository repository;

    @Test
    void loadsIndicationsAndSettingsForRequestedProcedures() {
        FeeScheduleSnapshot snapshot = repository.snapshotFor(List.of(procedure("PROC-SPIRO")), "ACME-HEALTH");

        assertThat(snapshot.payerKnown()).isTrue();
        assertThat(snapshot.procedures()).containsOnlyKeys(procedure("PROC-SPIRO"));
        FeeScheduleSnapshot.ProcedureRules spirometry =
                snapshot.rulesFor(procedure("PROC-SPIRO")).orElseThrow();
        assertThat(spirometry.allowedDiagnoses())
                .containsExactlyInAnyOrder(diagnosis("DX-ASTHMA"), diagnosis("DX-COPD"));
        assertThat(spirometry.allowedSettings()).containsExactlyInAnyOrder("AMB", "IMP");
    }

    @Test
    void unknownProceduresAreAbsent() {
        FeeScheduleSnapshot snapshot =
                repository.snapshotFor(List.of(procedure("PROC-SPIRO"), procedure("PROC-NOPE")), "ACME-HEALTH");

        assertThat(snapshot.procedures()).containsOnlyKeys(procedure("PROC-SPIRO"));
    }

    @Test
    void sameCodeInAnotherSystemIsUnknown() {
        FeeScheduleSnapshot snapshot = repository.snapshotFor(
                List.of(new com.openclaim.engine.claim.Coding("https://elsewhere.example.org", "PROC-SPIRO")), null);

        assertThat(snapshot.procedures()).isEmpty();
    }

    @Test
    void conflictsAreSymmetricAndOnlyAmongRequestedProcedures() {
        FeeScheduleSnapshot both =
                repository.snapshotFor(List.of(procedure("PROC-SPIRO-LTD"), procedure("PROC-SPIRO")), "ACME-HEALTH");
        FeeScheduleSnapshot one = repository.snapshotFor(List.of(procedure("PROC-SPIRO")), "ACME-HEALTH");

        assertThat(both.conflicting(procedure("PROC-SPIRO"), procedure("PROC-SPIRO-LTD")))
                .isTrue();
        assertThat(both.conflicting(procedure("PROC-SPIRO-LTD"), procedure("PROC-SPIRO")))
                .isTrue();
        assertThat(one.conflicts()).isEmpty();
    }

    @Test
    void payerRegistration() {
        assertThat(repository
                        .snapshotFor(List.of(procedure("PROC-ECG")), "NOBODY")
                        .payerKnown())
                .isFalse();
        assertThat(repository.snapshotFor(List.of(procedure("PROC-ECG")), null).payerKnown())
                .isFalse();
    }

    @Test
    void seededScheduleAdmitsTheReferenceClaimBundle() {
        FeeScheduleSnapshot snapshot =
                repository.snapshotFor(List.of(procedure("PROC-SPIRO"), procedure("PROC-CONSULT")), "ACME-HEALTH");

        assertThat(snapshot.procedures()).containsOnlyKeys(procedure("PROC-SPIRO"), procedure("PROC-CONSULT"));
        assertThat(snapshot.conflicts()).isEmpty();
    }
}
