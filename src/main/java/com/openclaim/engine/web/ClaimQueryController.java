package com.openclaim.engine.web;

import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.fhir.MinorUnits;
import com.openclaim.engine.ledger.Balances;
import com.openclaim.engine.ledger.Ledger;
import com.openclaim.engine.ledger.LedgerAccount;
import com.openclaim.engine.ledger.LedgerEntryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Operational view of a claim: lifecycle status, scrubber violations and its ledger. */
@RestController
public class ClaimQueryController {

    private final ClaimRepository claims;
    private final Ledger ledger;

    public ClaimQueryController(ClaimRepository claims, Ledger ledger) {
        this.claims = claims;
        this.ledger = ledger;
    }

    public record ClaimView(
            UUID id,
            String identifierSystem,
            String identifierValue,
            String status,
            @Nullable String payerCode,
            BigDecimal totalBilled,
            String currency,
            Instant serviceStart,
            Instant createdAt,
            List<ScrubViolation> violations,
            Map<LedgerAccount, BigDecimal> balances,
            List<EntryView> ledger) {}

    /** One posting, as the spec's FinancialLedgerEntry. */
    public record EntryView(
            UUID journalId,
            String entryType,
            String account,
            BigDecimal amount,
            String adjudicationReference,
            Instant transactionTime,
            Instant validTime) {}

    @GetMapping("/api/claims/{claimId}")
    public ResponseEntity<ClaimView> claim(@PathVariable UUID claimId) {
        return claims.findById(claimId)
                .map(this::view)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ErrorResponseException(
                        HttpStatus.NOT_FOUND,
                        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No claim " + claimId),
                        null));
    }

    private ClaimView view(Claim claim) {
        Currency currency = claim.currency();
        Balances balances = ledger.balances(claim.id());
        Map<LedgerAccount, BigDecimal> balanceView = new EnumMap<>(LedgerAccount.class);
        balances.byAccount()
                .forEach((account, minor) -> balanceView.put(account, MinorUnits.toDecimal(minor, currency)));
        List<EntryView> entries = ledger.entries(claim.id()).stream()
                .map((LedgerEntryView e) -> new EntryView(
                        e.journalId(),
                        e.entryType().name(),
                        e.account().name(),
                        MinorUnits.toDecimal(e.amountMinor(), currency),
                        e.adjudicationReference(),
                        e.transactionTime(),
                        e.validTime()))
                .toList();
        return new ClaimView(
                claim.id(),
                claim.identifier().system(),
                claim.identifier().value(),
                claim.status().name(),
                claim.payerCode(),
                MinorUnits.toDecimal(claim.totalBilledMinor(), currency),
                currency.getCurrencyCode(),
                claim.serviceStart(),
                claim.createdAt(),
                claim.violations(),
                balanceView,
                entries);
    }
}
