/**
 * Pre-submission scrubber (spec §4): an ordered chain of independent rules over the canonical claim. Rules are pure
 * functions of the submission and a {@link com.openclaim.engine.rules.FeeScheduleSnapshot}; none of them touches FHIR
 * types or the database.
 */
@NullMarked
package com.openclaim.engine.rules;

import org.jspecify.annotations.NullMarked;
