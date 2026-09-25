package com.openclaim.engine.web;

import ca.uhn.fhir.context.FhirContext;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.fhir.FhirMediaTypes;
import com.openclaim.engine.fhir.RemittanceMapper;
import com.openclaim.engine.remittance.AdjudicationReconciler;
import com.openclaim.engine.remittance.RemittanceRepository;
import com.openclaim.engine.remittance.RemittanceResult;
import com.openclaim.engine.remittance.StoredRemittance;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r5.model.ClaimResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payer remittance callback. The payer identifies itself with a {@code payer_code} claim in its access token and may
 * only adjudicate, and read back, its own claims.
 */
@RestController
public class ClaimResponseController {

    static final String PAYER_CODE_CLAIM = "payer_code";

    private final RemittanceMapper mapper;
    private final AdjudicationReconciler reconciler;
    private final RemittanceRepository remittances;
    private final ClaimRepository claims;
    private final FhirContext fhir;

    public ClaimResponseController(
            RemittanceMapper mapper,
            AdjudicationReconciler reconciler,
            RemittanceRepository remittances,
            ClaimRepository claims,
            FhirContext fhir) {
        this.mapper = mapper;
        this.reconciler = reconciler;
        this.remittances = remittances;
        this.claims = claims;
        this.fhir = fhir;
    }

    @PostMapping(
            path = "/fhir/ClaimResponse",
            consumes = {FhirMediaTypes.FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE},
            produces = FhirMediaTypes.FHIR_JSON_VALUE)
    public ResponseEntity<String> receive(@RequestBody ClaimResponse response, @AuthenticationPrincipal Jwt token) {
        String payerCode = payerCode(token);
        RemittanceResult result = reconciler.apply(mapper.map(response), payerCode, id -> {
            ClaimResponse stored = response.copy();
            stored.setId(id.toString());
            return fhir.newJsonParser().encodeResourceToString(stored);
        });
        StoredRemittance remittance = result.remittance();
        ResponseEntity.BodyBuilder builder = result.replayed()
                ? ResponseEntity.ok()
                : ResponseEntity.created(URI.create("/fhir/ClaimResponse/" + remittance.id()));
        return builder.contentType(FhirMediaTypes.FHIR_JSON).body(remittance.resource());
    }

    @GetMapping(path = "/fhir/ClaimResponse/{id}", produces = FhirMediaTypes.FHIR_JSON_VALUE)
    public ResponseEntity<String> read(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        String payerCode = token.getClaimAsString(PAYER_CODE_CLAIM);
        return remittances
                .findById(id)
                .filter(stored -> payerCode == null
                        || claims.findById(stored.claimId())
                                .map(claim -> Objects.equals(claim.payerCode(), payerCode))
                                .orElse(false))
                .map(stored -> ResponseEntity.ok()
                        .contentType(FhirMediaTypes.FHIR_JSON)
                        .body(stored.resource()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String payerCode(Jwt token) {
        String payerCode = token.getClaimAsString(PAYER_CODE_CLAIM);
        if (payerCode == null || payerCode.isBlank()) {
            throw new AccessDeniedException("The access token does not identify a payer");
        }
        return payerCode;
    }
}
