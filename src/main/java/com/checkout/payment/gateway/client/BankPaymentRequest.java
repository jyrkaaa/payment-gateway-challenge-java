package com.checkout.payment.gateway.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BankPaymentRequest {

    @JsonProperty("card_number")
    private String cardNumber;

    @JsonProperty("expiry_date")
    private String expiryDate;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("amount")
    private int amount;

    @JsonProperty("cvv")
    private String cvv;
}
