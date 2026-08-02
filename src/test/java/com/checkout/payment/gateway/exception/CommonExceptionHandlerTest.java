package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

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
}
