package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommonExceptionHandlerTest {

    private CommonExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CommonExceptionHandler();
    }

    @Test
    void paymentNotFoundException_returns404() {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(new PaymentNotFoundException("id-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).isEqualTo("Payment not found");
    }

    @Test
    void paymentValidationException_returns422WithJoinedErrors() {
        PaymentValidationException ex = new PaymentValidationException(List.of("field A invalid", "field B required"));

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().getMessage()).isEqualTo("field A invalid; field B required");
    }

    @Test
    void bankCommunicationException_returns502() {
        ResponseEntity<ErrorResponse> response = handler.handleBankUnavailable(new BankCommunicationException("timeout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().getMessage()).isEqualTo("Acquiring bank is currently unavailable");
    }

    @Test
    void idempotencyKeyReuseException_returns409() {
        ResponseEntity<ErrorResponse> response = handler.handleIdempotencyKeyReuse(new IdempotencyKeyReuseException("my-key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getMessage()).contains("my-key");
    }

    @Test
    void methodArgumentNotValidException_returns422WithFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("obj", "cardNumber", "card_number is required");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        when(bindingResult.getGlobalErrors()).thenReturn(List.of());

        ResponseEntity<ErrorResponse> response = handler.handleBeanValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().getMessage()).isEqualTo("card_number is required");
    }

    @Test
    void methodArgumentNotValidException_combinesFieldAndGlobalErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("obj", "cardNumber", "card_number is required");
        ObjectError globalError = new ObjectError("obj", "expiry date must be in the future");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        when(bindingResult.getGlobalErrors()).thenReturn(List.of(globalError));

        ResponseEntity<ErrorResponse> response = handler.handleBeanValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().getMessage())
            .isEqualTo("card_number is required; expiry date must be in the future");
    }

    @Test
    void noResourceFoundException_returns404WithPathDetails() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "unknown/path", "unknown/path");

        ResponseEntity<ErrorResponse> response = handler.handleNoResource(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).contains("GET").contains("unknown/path");
    }

    @Test
    void httpMessageNotReadableException_floatForIntField_returns422WithFieldDetails() {
        InvalidFormatException ife = InvalidFormatException.from(null, "not a valid Integer value", "105.4", Integer.class);
        ife.prependPath(new Object(), "amount");
        HttpMessageNotReadableException ex =
            new HttpMessageNotReadableException("JSON parse error", ife, null);

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableMessage(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().getMessage()).isEqualTo("amount must be a whole number, got '105.4'");
    }

    @Test
    void httpMessageNotReadableException_withoutFieldDetails_returns422WithGenericMessage() {
        HttpMessageNotReadableException ex =
            new HttpMessageNotReadableException("Malformed JSON", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableMessage(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().getMessage()).isEqualTo("Request body is malformed");
    }

    @Test
    void constraintViolationException_returns422WithViolationMessages() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("amount must be a positive integer");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().getMessage()).isEqualTo("amount must be a positive integer");
    }
}
