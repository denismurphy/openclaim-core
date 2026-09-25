package com.openclaim.engine.web;

import com.openclaim.engine.config.OpenClaimProperties;
import com.openclaim.engine.fhir.MinorUnits;
import com.openclaim.engine.remittance.PatientPaymentService;
import com.openclaim.engine.remittance.PaymentResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PatientPaymentController {

    private final PatientPaymentService payments;
    private final Currency currency;

    public PatientPaymentController(PatientPaymentService payments, OpenClaimProperties properties) {
        this.payments = payments;
        this.currency = properties.currency();
    }

    public record PaymentRequest(
            @NotNull @Positive BigDecimal amount, @NotBlank String currency) {}

    public record PaymentResponse(UUID claimId, String status, BigDecimal outstanding, String currency) {}

    /**
     * Books a payment. {@code Idempotency-Key} is required so a retried request cannot book the payment twice; it is
     * scoped to the claim.
     */
    @PostMapping("/api/claims/{claimId}/patient-payments")
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable UUID claimId,
            @RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp = "[\\x21-\\x7E]{1,128}") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        if (!currency.getCurrencyCode().equals(request.currency())) {
            throw new InvalidPaymentException("Only %s payments are accepted".formatted(currency.getCurrencyCode()));
        }
        long amountMinor;
        try {
            amountMinor = MinorUnits.toMinor(request.amount(), currency);
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentException(e.getMessage());
        }
        PaymentResult result = payments.pay(claimId, idempotencyKey, amountMinor);
        PaymentResponse body = new PaymentResponse(
                claimId,
                result.claimStatus().name(),
                MinorUnits.toDecimal(result.outstandingMinor(), currency),
                currency.getCurrencyCode());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(body);
    }

    static class InvalidPaymentException extends RuntimeException {
        InvalidPaymentException(String message) {
            super(message);
        }
    }
}
