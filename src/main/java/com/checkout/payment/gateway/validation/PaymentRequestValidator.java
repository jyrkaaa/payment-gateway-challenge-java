package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PaymentRequestValidator {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "GBP", "EUR");

    public List<String> validate(PostPaymentRequest request) {
        List<String> errors = new ArrayList<>();
        String currency = request.getCurrency();
        if (currency != null && currency.length() == 3 && !SUPPORTED_CURRENCIES.contains(currency)) {
            errors.add("currency must be one of: USD, GBP, EUR");
        }
        return errors;
    }
}
