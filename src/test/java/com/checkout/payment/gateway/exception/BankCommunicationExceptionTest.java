package com.checkout.payment.gateway.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankCommunicationExceptionTest {

    @Test
    void messageOnlyConstructor_setsMessageAndNoCause() {
        BankCommunicationException ex = new BankCommunicationException("bank down");

        assertThat(ex.getMessage()).isEqualTo("bank down");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor_setsBoth() {
        Throwable cause = new RuntimeException("root cause");
        BankCommunicationException ex = new BankCommunicationException("bank down", cause);

        assertThat(ex.getMessage()).isEqualTo("bank down");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void bankServiceUnavailableException_isASubtypeOfBankCommunicationException() {
        BankServiceUnavailableException ex = new BankServiceUnavailableException("circuit open");

        assertThat(ex).isInstanceOf(BankCommunicationException.class);
        assertThat(ex.getMessage()).isEqualTo("circuit open");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void bankServiceUnavailableException_messageAndCauseConstructor_setsBoth() {
        Throwable cause = new RuntimeException("root cause");
        BankServiceUnavailableException ex = new BankServiceUnavailableException("circuit open", cause);

        assertThat(ex.getMessage()).isEqualTo("circuit open");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
