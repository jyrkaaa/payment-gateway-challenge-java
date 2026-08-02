package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.client.IBankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.idempotency.IIdempotencyStore;
import com.checkout.payment.gateway.idempotency.IdempotencyResult;
import com.checkout.payment.gateway.mappers.BankPaymentMapper;
import com.checkout.payment.gateway.mappers.PaymentMapper;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.model.ProcessedPayment;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentGatewayService implements IPaymentGatewayService {

    private final PaymentsRepository paymentsRepository;
    private final IBankClient bankClient;
    private final IIdempotencyStore idempotencyService;
    private final MeterRegistry meterRegistry;

    @Override
    public PostPaymentResponse getPaymentById(UUID id) {
        log.debug("Retrieving payment {}", id);
        return paymentsRepository.get(id)
            .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + id));
    }

    @Override
    public ProcessedPayment processPayment(PostPaymentRequest request, Optional<String> idempotencyKey) {
        log.debug("Processing payment: last4={} amount={} currency={} idempotencyKey={}",
        request.getCardNumberLastFour(), request.getAmount(), request.getCurrency(),
        idempotencyKey.isPresent() ? "<present>" : "<none>");

        try {
            ProcessedPayment result = idempotencyKey.isPresent()
                ? processIdempotent(request, idempotencyKey.get())
                : new ProcessedPayment(doProcess(request), false);
            return result;
        } catch (RuntimeException ex) {
            log.warn("Payment failed last4={} cause={}",
            request.getCardNumberLastFour(), ex.toString());
            throw ex;
        }
    }

    private ProcessedPayment processIdempotent(PostPaymentRequest request, String idempotencyKey) {
    IdempotencyResult result = idempotencyService.computeIfAbsent(idempotencyKey, FingerprintService.fingerprint(request),
     () -> doProcess(request));

    if (result.replayed()) {
      log.info("Replayed idempotent payment id={} key={}",
          result.response().getId(), idempotencyKey);
    }

    return new ProcessedPayment(result.response(), result.replayed());
  }

  private PostPaymentResponse doProcess(PostPaymentRequest request) {
    BankPaymentResponse bankResponse = bankClient.sendPayment(
        BankPaymentMapper.from(request));

    PaymentStatus status = bankResponse.isAuthorized()
        ? PaymentStatus.AUTHORIZED
        : PaymentStatus.DECLINED;

    PostPaymentResponse response =
        PaymentMapper.toPaymentResponse(UUID.randomUUID(), status, request);
    paymentsRepository.add(response);
    meterRegistry.counter("payments.processed",
        "status", status.getName(), "currency", request.getCurrency()).increment();
    return response;
  }
}
