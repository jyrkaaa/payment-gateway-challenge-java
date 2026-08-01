package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.UUID;
import java.math.BigDecimal;

@Data
public class PostPaymentResponse {
    @JsonProperty("id")                    
    private UUID id;
    @JsonProperty("status")               
    private PaymentStatus status;
    @JsonProperty("card_number_last_four") 
    private int cardNumberLastFour;
    @JsonProperty("expiry_month")          
    private int expiryMonth;
    @JsonProperty("expiry_year")           
    private int expiryYear;
    @JsonProperty("currency")              
    private String currency;
    @JsonProperty("amount")                
    private BigDecimal amount;
}
