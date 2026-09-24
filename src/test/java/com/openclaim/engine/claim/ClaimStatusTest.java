package com.openclaim.engine.claim;

import static com.openclaim.engine.claim.ClaimStatus.ADJUDICATED_PARTIAL;
import static com.openclaim.engine.claim.ClaimStatus.DISPATCHED;
import static com.openclaim.engine.claim.ClaimStatus.DRAFT;
import static com.openclaim.engine.claim.ClaimStatus.PRE_VALIDATED;
import static com.openclaim.engine.claim.ClaimStatus.REJECTED_SCRUBBER;
import static com.openclaim.engine.claim.ClaimStatus.SETTLED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClaimStatusTest {

    /** The lifecycle from spec §3, written out independently of the implementation. */
    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(PRE_VALIDATED, REJECTED_SCRUBBER),
            PRE_VALIDATED, Set.of(DISPATCHED),
            DISPATCHED, Set.of(SETTLED, ADJUDICATED_PARTIAL),
            ADJUDICATED_PARTIAL, Set.of(SETTLED),
            REJECTED_SCRUBBER, Set.of(),
            SETTLED, Set.of());

    static Stream<Arguments> everyPair() {
        return Arrays.stream(ClaimStatus.values())
                .flatMap(from -> Arrays.stream(ClaimStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlySpecifiedTransitionsAreAllowed(ClaimStatus from, ClaimStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.get(from).contains(to));
    }

    @ParameterizedTest
    @MethodSource("everyPair")
    void tableCoversEveryStatus(ClaimStatus from, ClaimStatus to) {
        assertThat(ALLOWED).containsKeys(from, to);
    }
}
