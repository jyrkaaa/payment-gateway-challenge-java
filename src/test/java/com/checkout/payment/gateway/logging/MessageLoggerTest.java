package com.checkout.payment.gateway.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MessageLoggerTest {

    @Test
    void logInbound_withBodies_clearsMdcAfterLogging() {
        MessageLogger.logInbound("POST", "/api/v1/payments", 201, 42,
            "{\"cvv\":\"123\"}", "{\"status\":\"Authorized\"}");

        assertThat(MDC.get("event_type")).isNull();
        assertThat(MDC.get("msg_direction")).isNull();
        assertThat(MDC.get("msg_request")).isNull();
        assertThat(MDC.get("msg_response")).isNull();
    }

    @Test
    void logInbound_withNullBodies_doesNotThrow() {
        MessageLogger.logInbound("GET", "/api/v1/payments/123", 200, 5, null, null);

        assertThat(MDC.get("msg_request")).isNull();
        assertThat(MDC.get("msg_response")).isNull();
    }

    @Test
    void logOutbound_success_clearsMdcAfterLogging() {
        MessageLogger.logOutbound("POST", "/payments", "acquiring-bank", 200, 10, true,
            "{\"cvv\":\"123\"}", "{\"authorized\":true}");

        assertThat(MDC.get("event_type")).isNull();
        assertThat(MDC.get("msg_status")).isNull();
    }

    @Test
    void logOutbound_failure_clearsMdcAfterLogging() {
        MessageLogger.logOutbound("POST", "/payments", "acquiring-bank", 503, 10, false,
            "{\"cvv\":\"123\"}", null);

        assertThat(MDC.get("event_type")).isNull();
    }

    @Test
    void logOutbound_nullStatusCode_doesNotThrow() {
        MessageLogger.logOutbound("POST", "/payments", "acquiring-bank", null, 10, false,
            null, null);

        assertThat(MDC.get("msg_status")).isNull();
    }
}
