package com.openclaim.engine.web;

import com.openclaim.engine.remittance.PaymentRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem details for the non-FHIR {@code /api/**} endpoints. Spring's base class already covers malformed
 * bodies, bean validation and missing headers.
 */
@RestControllerAdvice(assignableTypes = {PatientPaymentController.class, ClaimQueryController.class})
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ProblemDetail paymentRejected(PaymentRejectedException e) {
        HttpStatus status = switch (e.reason()) {
            case UNKNOWN_CLAIM -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case OVERPAYMENT -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidPayment(PatientPaymentController.InvalidPaymentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
}
