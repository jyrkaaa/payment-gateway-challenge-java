package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.idempotency.IdempotencyResult;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.model.ProcessedPayment;

import java.util.Optional;
import java.util.UUID;

public interface IPaymentGatewayService {

    PostPaymentResponse getPaymentById(UUID id);

    ProcessedPayment processPayment(PostPaymentRequest request, Optional<String> idempotencyKey);
}
