package com.openclaim.engine.rules;

import com.openclaim.engine.claim.Coding;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

public interface FeeScheduleRepository {

    /** Loads the fee schedule entries and conflict pairs relevant to {@code procedures}, plus the payer's registration. */
    FeeScheduleSnapshot snapshotFor(Collection<Coding> procedures, @Nullable String payerCode);
}
