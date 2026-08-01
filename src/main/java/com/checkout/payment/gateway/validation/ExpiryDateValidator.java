package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.YearMonth;

public class ExpiryDateValidator implements ConstraintValidator<ValidExpiryDate, PostPaymentRequest> {

    @Override
    public boolean isValid(PostPaymentRequest request, ConstraintValidatorContext context) {
        if (request == null) return true;
        int month = request.getExpiryMonth();
        int year  = request.getExpiryYear();
        if (month < 1 || month > 12) return true;
        try {
            return YearMonth.of(year, month).isAfter(YearMonth.now());
        } catch (Exception e) {
            return false;
        }
    }
}
