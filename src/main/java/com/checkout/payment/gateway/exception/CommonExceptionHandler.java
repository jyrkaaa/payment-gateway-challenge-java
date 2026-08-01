package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;
import java.util.stream.Stream;

@Slf4j
@ControllerAdvice
public class CommonExceptionHandler {

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
        return ResponseEntity.unprocessableEntity()
            .body(new ErrorResponse(String.join("; ", errors)));
    }

    @ExceptionHandler(PaymentValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(PaymentValidationException ex) {
        log.warn("Payment rejected: {}", ex.getErrors());
        return ResponseEntity.unprocessableEntity()
            .body(new ErrorResponse(String.join("; ", ex.getErrors())));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations().stream()
            .map(cv -> cv.getMessage())
            .toList();
        log.warn("Request rejected by constraint validation: {}", errors);
        return ResponseEntity.unprocessableEntity()
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
