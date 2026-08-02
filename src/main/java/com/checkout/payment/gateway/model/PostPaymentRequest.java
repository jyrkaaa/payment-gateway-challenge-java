package com.checkout.payment.gateway.model;
import java.io.Serializable;
import com.checkout.payment.gateway.validation.ValidCurrency;
import com.checkout.payment.gateway.validation.ValidExpiryDate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;
import lombok.ToString;

@Data
@ValidExpiryDate
@ValidCurrency
@ToString(exclude = {"cardNumber", "cvv"})
public class PostPaymentRequest implements Serializable {

    @NotBlank(message = "card_number is required")
    @Pattern(regexp = "^[0-9]{14,19}$", message = "card_number must be 14-19 numeric digits")
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

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be a positive integer")
    @Digits(integer = 9, fraction = 0, message = "amount must be a whole number")
    private BigDecimal amount;

    @NotBlank(message = "cvv is required")
    @Pattern(regexp = "^[0-9]{3,4}$", message = "cvv must be 3-4 numeric digits")
    private String cvv;

    @JsonIgnore
    public String getExpiryDate() {
        return String.format("%02d/%d", expiryMonth, expiryYear);
    }

    @JsonIgnore
    public String getCardNumberLastFour() {
        return cardNumber != null && cardNumber.length() >= 4
            ? cardNumber.substring(cardNumber.length() - 4)
            : cardNumber;
    }
}
