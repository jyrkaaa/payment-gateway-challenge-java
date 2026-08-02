package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@ControllerAdvice
public class CommonExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("No endpoint " + ex.getHttpMethod() + " " + ex.getResourcePath()));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException ex) {
        log.warn("Payment not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("Payment not found"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> errors = Stream.concat(
            ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getDefaultMessage()),
            ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> ge.getDefaultMessage())
        ).toList();
        log.warn("Payment rejected by validation: {}", errors);
        return ResponseEntity.unprocessableContent()
            .body(new ErrorResponse(String.join("; ", errors)));
    }

    @ExceptionHandler(PaymentValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(PaymentValidationException ex) {
        log.warn("Payment rejected: {}", ex.getErrors());
        return ResponseEntity.unprocessableContent()
            .body(new ErrorResponse(String.join("; ", ex.getErrors())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        String message = invalidNumericFieldMessage(ex.getCause()).orElse("Request body is malformed");
        log.warn("Payment rejected - malformed request body: {}", ex.getMessage());
        return ResponseEntity.unprocessableContent().body(new ErrorResponse(message));
    }

    private Optional<String> invalidNumericFieldMessage(Throwable cause) {
        if (cause instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(0).getPropertyName();
            return Optional.of(field + " must be a whole number, got '" + ife.getValue() + "'");
        }
        return Optional.empty();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations().stream()
            .map(cv -> cv.getMessage())
            .toList();
        log.warn("Request rejected by constraint validation: {}", errors);
        return ResponseEntity.unprocessableContent()
            .body(new ErrorResponse(String.join("; ", errors)));
    }

    @ExceptionHandler(BankCommunicationException.class)
    public ResponseEntity<ErrorResponse> handleBankUnavailable(BankCommunicationException ex) {
        log.error("Bank unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new ErrorResponse("Acquiring bank is currently unavailable"));
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyReuse(IdempotencyKeyReuseException ex) {
        log.warn("Idempotency key reuse: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse(ex.getMessage()));
    }
}
