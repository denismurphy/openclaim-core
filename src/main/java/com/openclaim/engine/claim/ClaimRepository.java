package com.openclaim.engine.claim;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.ListCrudRepository;

public interface ClaimRepository extends ListCrudRepository<Claim, UUID> {

    /**
     * Loads the claim holding a row lock ({@code SELECT ... FOR UPDATE}) until the surrounding transaction ends.
     * Every command that changes a claim's status or posts to its ledger takes this lock first, so balance checks and
     * transitions on one claim are serialised, and locks are always acquired claim-row-first.
     */
    @Lock(LockMode.PESSIMISTIC_WRITE)
    Optional<Claim> findLockedById(UUID id);

    Optional<Claim> findByIdentifierSystemAndIdentifierValue(String identifierSystem, String identifierValue);
}
