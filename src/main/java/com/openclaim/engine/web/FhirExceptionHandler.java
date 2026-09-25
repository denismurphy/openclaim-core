package com.openclaim.engine.web;

import com.openclaim.engine.fhir.FhirMappingException;
import com.openclaim.engine.fhir.FhirMediaTypes;
import com.openclaim.engine.fhir.FhirResourceHttpMessageConverter.PayloadTooLargeException;
import com.openclaim.engine.fhir.OperationOutcomes;
import com.openclaim.engine.ingest.ClaimConflictException;
import com.openclaim.engine.remittance.RemittanceRejectedException;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders failures on {@code /fhir/**} endpoints as OperationOutcome. Messages come from the parser, the mapper or
 * this service, never from stack traces, and unexpected errors expose nothing but a generic message.
 */
@RestControllerAdvice(assignableTypes = {ClaimSubmitController.class, ClaimResponseController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class FhirExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FhirExceptionHandler.class);

    @ExceptionHandler
    ResponseEntity<OperationOutcome> tooLarge(PayloadTooLargeException e) {
        return respond(HttpStatus.CONTENT_TOO_LARGE, OperationOutcomes.error(IssueType.TOOLONG, e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<OperationOutcome> unreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, OperationOutcomes.error(IssueType.STRUCTURE, e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<OperationOutcome> unmappable(FhirMappingException e) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, OperationOutcomes.from(e));
    }

    @ExceptionHandler
    ResponseEntity<OperationOutcome> conflict(ClaimConflictException e) {
        return respond(HttpStatus.CONFLICT, OperationOutcomes.error(IssueType.DUPLICATE, e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<OperationOutcome> remittanceRejected(RemittanceRejectedException e) {
        return switch (e.reason()) {
            case UNKNOWN_CLAIM ->
                respond(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        OperationOutcomes.error(IssueType.NOTFOUND, e.getMessage(), "ClaimResponse.request"));
            case UNRECONCILED ->
                respond(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        OperationOutcomes.error(IssueType.BUSINESSRULE, e.getMessage(), "ClaimResponse.total"));
            case CONFLICT -> respond(HttpStatus.CONFLICT, OperationOutcomes.error(IssueType.CONFLICT, e.getMessage()));
        };
    }

    /** Let Spring Security translate authorisation failures (403) instead of the catch-all below. */
    @ExceptionHandler
    void accessDenied(AccessDeniedException e) {
        throw e;
    }

    @ExceptionHandler
    ResponseEntity<OperationOutcome> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                OperationOutcomes.error(
                        IssueType.NOTSUPPORTED, "Send the resource as " + FhirMediaTypes.FHIR_JSON_VALUE));
    }

    @ExceptionHandler
    ResponseEntity<OperationOutcome> unexpected(Exception e) {
        log.error("Unhandled error on FHIR endpoint", e);
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                OperationOutcomes.error(IssueType.EXCEPTION, "Internal error; the request was not processed"));
    }

    private static ResponseEntity<OperationOutcome> respond(HttpStatus status, OperationOutcome outcome) {
        return ResponseEntity.status(status)
                .contentType(FhirMediaTypes.FHIR_JSON)
                .body(outcome);
    }
}
