package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.validation.ValidExpiryDate;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@ValidExpiryDate
public class PostPaymentRequest {

    @NotBlank(message = "card_number is required")
    @Pattern(regexp = "\\d{14,19}", message = "card_number must be 14-19 numeric characters")
    @JsonProperty("card_number")
    private String cardNumber;

    @Min(value = 1, message = "expiry_month must be between 1 and 12")
    @Max(value = 12, message = "expiry_month must be between 1 and 12")
    @JsonProperty("expiry_month")
    private int expiryMonth;

    @JsonProperty("expiry_year")
    private int expiryYear;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be 3 characters")
    private String currency;

    @Positive(message = "amount must be a positive integer")
    private int amount;

    @NotBlank(message = "cvv is required")
    @Pattern(regexp = "\\d{3,4}", message = "cvv must be 3-4 numeric characters")
    private String cvv;

    public String getExpiryDate() {
        return String.format("%02d/%d", expiryMonth, expiryYear);
    }

    public String getCardNumberLastFour() {
        return cardNumber != null && cardNumber.length() >= 4
            ? cardNumber.substring(cardNumber.length() - 4)
            : cardNumber;
    }
}
