package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.PostPaymentResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentsRepository implements IPaymentsRepository {

  private final ConcurrentHashMap<UUID, PostPaymentResponse> payments = new ConcurrentHashMap<>();

  public void add(PostPaymentResponse payment) {
    payments.put(payment.getId(), payment);
  }

  public Optional<PostPaymentResponse> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

}
