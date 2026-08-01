package com.checkout.payment.gateway.logging;

import lombok.experimental.UtilityClass;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

@UtilityClass
public class MessageLogger {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger("MESSAGE_LOG");

    private static final List<String> MSG_KEYS = List.of(
        "event_type", "msg_direction", "msg_method", "msg_endpoint",
        "msg_sender", "msg_target", "msg_status", "msg_duration_ms"
    );

    public void logInbound(String method, String endpoint, String sender,
                           int statusCode, long durationMs) {
        MDC.put("event_type",      "MSG_LOG");
        MDC.put("msg_direction",   "INBOUND");
        MDC.put("msg_method",      method);
        MDC.put("msg_endpoint",    endpoint);
        MDC.put("msg_sender",      sender != null ? sender : "unknown");
        MDC.put("msg_target",      "payment-gateway");
        MDC.put("msg_status",      String.valueOf(statusCode));
        MDC.put("msg_duration_ms", String.valueOf(durationMs));
        try {
            log.info("{} {} -> {} ({}ms)", method, endpoint, statusCode, durationMs);
        } finally {
            MSG_KEYS.forEach(MDC::remove);
        }
    }

    public void logOutbound(String method, String endpoint, String target,
                            Integer statusCode, long durationMs, boolean success) {
        MDC.put("event_type",      "MSG_LOG");
        MDC.put("msg_direction",   "OUTBOUND");
        MDC.put("msg_method",      method);
        MDC.put("msg_endpoint",    endpoint);
        MDC.put("msg_sender",      "payment-gateway");
        MDC.put("msg_target",      target);
        MDC.put("msg_status",      statusCode != null ? String.valueOf(statusCode) : "N/A");
        MDC.put("msg_duration_ms", String.valueOf(durationMs));
        try {
            if (success) {
                log.info("{} {} -> {} ({}ms)", method, endpoint, statusCode, durationMs);
            } else {
                log.warn("{} {} -> {} FAILED ({}ms)", method, endpoint, statusCode, durationMs);
            }
        } finally {
            MSG_KEYS.forEach(MDC::remove);
        }
    }
}
