package com.checkout.payment.gateway.client;

import org.springframework.http.HttpStatusCode;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BankPaymentResponse implements RestApiResponse {
    @JsonProperty("authorized")
    private boolean authorized;

    @JsonProperty("authorization_code")
    private String authorizationCode;

    @JsonIgnore
    private HttpStatusCode statusCode;
}
