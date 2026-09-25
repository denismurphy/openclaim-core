package com.openclaim.engine.remittance;

import com.openclaim.engine.claim.ClaimIdentifier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RemittanceRepository {

    private final JdbcClient jdbc;

    public RemittanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID claimId, Adjudication adjudication, String resource) {
        jdbc.sql("""
                        INSERT INTO remittance (id, claim_id, identifier_system, identifier_value, content_hash,
                            submitted_minor, eligible_minor, benefit_minor, write_off_minor, patient_liability_minor,
                            resource)
                        VALUES (:id, :claimId, :system, :value, :hash, :submitted, :eligible, :benefit, :writeOff,
                            :liability, :resource)""")
                .param("id", id)
                .param("claimId", claimId)
                .param("system", adjudication.identifier().system())
                .param("value", adjudication.identifier().value())
                .param("hash", adjudication.fingerprint())
                .param("submitted", adjudication.submittedMinor())
                .param("eligible", adjudication.eligibleMinor())
                .param("benefit", adjudication.benefitMinor())
                .param("writeOff", adjudication.writeOffMinor())
                .param("liability", adjudication.patientLiabilityMinor())
                .param("resource", resource)
                .update();
    }

    public Optional<StoredRemittance> findByIdentifier(ClaimIdentifier identifier) {
        return jdbc.sql("""
                        SELECT id, claim_id, content_hash, resource FROM remittance
                        WHERE identifier_system = :system AND identifier_value = :value""")
                .param("system", identifier.system())
                .param("value", identifier.value())
                .query(StoredRemittance.class)
                .optional();
    }

    public Optional<StoredRemittance> findById(UUID id) {
        return jdbc.sql("SELECT id, claim_id, content_hash, resource FROM remittance WHERE id = :id")
                .param("id", id)
                .query(StoredRemittance.class)
                .optional();
    }
}
