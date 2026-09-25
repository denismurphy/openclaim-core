package com.openclaim.engine.ingest;

import com.openclaim.engine.claim.Claim;

/** @param replayed true when this submission repeated an earlier one and nothing new was stored */
public record SubmissionResult(Claim claim, boolean replayed) {}
