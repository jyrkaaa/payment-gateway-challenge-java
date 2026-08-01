package com.checkout.payment.gateway.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BankPaymentResponse {
    @JsonProperty("authorized")
    private boolean authorized;

    @JsonProperty("authorization_code")
    private String authorizationCode;
}
