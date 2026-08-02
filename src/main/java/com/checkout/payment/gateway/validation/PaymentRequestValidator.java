package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class PaymentRequestValidator implements ConstraintValidator<ValidCurrency, PostPaymentRequest> {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "GBP", "EUR");

    @Override
    public boolean isValid(PostPaymentRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true; // consider null as valid, use @NotNull for null check
        }
        String currency = request.getCurrency();
        if (currency != null && !SUPPORTED_CURRENCIES.contains(currency)) {
            return false;
        }
        return true;
    }
}
