package com.checkout.payment.gateway.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@UtilityClass
public class SensitiveDataMasker {

    private final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<String> REDACT_KEYS = Set.of("cvv");
    private final Set<String> PARTIAL_MASK_KEYS = Set.of("card_number");

    private final Set<String> REDACT_HEADER_KEYS = Set.of(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"
    );

    public String maskHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((key, value) ->
            masked.put(key, REDACT_HEADER_KEYS.contains(key.toLowerCase()) ? "***" : value));
        try {
            return MAPPER.writeValueAsString(masked);
        } catch (Exception e) {
            return "[unparseable]";
        }
    }

    public String mask(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode node = MAPPER.readTree(json);
            maskNode(node);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "[unparseable]";
        }
    }

    private void maskNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            obj.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                if (REDACT_KEYS.contains(key)) {
                    obj.put(key, "***");
                } else if (PARTIAL_MASK_KEYS.contains(key)) {
                    obj.put(key, maskCardNumber(obj.get(key).asText()));
                } else {
                    maskNode(obj.get(key));
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> maskNode(child));
        }
    }

    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) return "***";
        int visibleStart = cardNumber.length() - 4;
        return "*".repeat(visibleStart) + cardNumber.substring(visibleStart);
    }
}
