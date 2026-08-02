package com.checkout.payment.gateway.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseTest {

    @Test
    void getMessage_returnsConstructorValue() {
        ErrorResponse response = new ErrorResponse("something went wrong");

        assertThat(response.getMessage()).isEqualTo("something went wrong");
    }

    @Test
    void toString_containsMessage() {
        ErrorResponse response = new ErrorResponse("something went wrong");

        assertThat(response.toString()).isEqualTo("ErrorResponse{message='something went wrong'}");
    }
}
