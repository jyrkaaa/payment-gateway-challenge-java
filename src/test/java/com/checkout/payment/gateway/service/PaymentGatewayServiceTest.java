package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.client.IBankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.IdempotencyKeyReuseException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.idempotency.IIdempotencyStore;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.model.ProcessedPayment;
import com.checkout.payment.gateway.repository.IPaymentsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentGatewayServiceTest {

    @Mock 
    IPaymentsRepository repository;
    @Mock 
    IBankClient bankClient;
    @Mock 
    IIdempotencyStore idempotencyStore;

    private PaymentGatewayService service;

    @BeforeEach
    void setUp() {
        service = new PaymentGatewayService(repository, bankClient, idempotencyStore, new SimpleMeterRegistry());
    }

    @Test
    void processPayment_noIdempotencyKey_authorized_storesAndReturnsFresh() {
        PostPaymentRequest request = validRequest();
        BankPaymentResponse bankResp = mock(BankPaymentResponse.class);
        when(bankResp.isAuthorized()).thenReturn(true);
        when(bankClient.sendPayment(any())).thenReturn(bankResp);

        ProcessedPayment result = service.processPayment(request, Optional.empty());

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.response().getId()).isNotNull();
        assertThat(result.response().getCardNumberLastFour()).isEqualTo("8877");
        verify(repository).add(result.response());
    }

    @Test
    void processPayment_noIdempotencyKey_declined_storesAndReturnsFresh() {
        PostPaymentRequest request = validRequest();
        BankPaymentResponse bankResp = mock(BankPaymentResponse.class);
        when(bankResp.isAuthorized()).thenReturn(false);
        when(bankClient.sendPayment(any())).thenReturn(bankResp);

        ProcessedPayment result = service.processPayment(request, Optional.empty());

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(PaymentStatus.DECLINED);
        verify(repository).add(result.response());
    }

    @Test
    void processPayment_withIdempotencyKey_cacheHit_returnsReplayedWithoutCallingBankAgain() {
        PostPaymentRequest request = validRequest();
        BankPaymentResponse bankResp = mock(BankPaymentResponse.class);
        when(bankResp.isAuthorized()).thenReturn(true);
        when(bankClient.sendPayment(any())).thenReturn(bankResp);

        ProcessedPayment first = service.processPayment(request, Optional.of("key-abc"));
        ProcessedPayment replayed = service.processPayment(request, Optional.of("key-abc"));

        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.response()).isSameAs(first.response());
        verify(bankClient, times(1)).sendPayment(any());
        verify(repository, times(1)).add(any());
    }

    @Test
    void processPayment_withIdempotencyKey_cacheMiss_processesAndStoresResponse() {
        PostPaymentRequest request = validRequest();
        BankPaymentResponse bankResp = mock(BankPaymentResponse.class);
        when(bankResp.isAuthorized()).thenReturn(true);
        when(bankClient.sendPayment(any())).thenReturn(bankResp);

        ProcessedPayment result = service.processPayment(request, Optional.of("key-new"));

        assertThat(result.replayed()).isFalse();
        verify(repository).add(result.response());
    }

    @Test
    void processPayment_sameKeyDifferentRequest_throwsIdempotencyKeyReuseException() {
        PostPaymentRequest request1 = validRequest();
        PostPaymentRequest request2 = validRequest();
        request2.setAmount(BigDecimal.valueOf(99900));
        BankPaymentResponse bankResp = mock(BankPaymentResponse.class);
        when(bankResp.isAuthorized()).thenReturn(true);
        when(bankClient.sendPayment(any())).thenReturn(bankResp);

        service.processPayment(request1, Optional.of("key-x"));

        assertThatThrownBy(() -> service.processPayment(request2, Optional.of("key-x")))
            .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void processPayment_bankThrows_propagatesBankCommunicationException() {
        PostPaymentRequest request = validRequest();
        when(bankClient.sendPayment(any())).thenThrow(new BankCommunicationException("Bank unavailable"));

        assertThatThrownBy(() -> service.processPayment(request, Optional.empty()))
            .isInstanceOf(BankCommunicationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void getPaymentById_knownId_returnsPayment() {
        PostPaymentResponse stored = new PostPaymentResponse();
        stored.setId(UUID.randomUUID());
        when(repository.get(stored.getId())).thenReturn(Optional.of(stored));

        assertThat(service.getPaymentById(stored.getId())).isEqualTo(stored);
    }

    @Test
    void getPaymentById_unknownId_throwsPaymentNotFoundException() {
        UUID id = UUID.randomUUID();
        when(repository.get(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPaymentById(id))
            .isInstanceOf(PaymentNotFoundException.class);
    }

    private PostPaymentRequest validRequest() {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setCardNumber("2222405343248877");
        r.setExpiryMonth(4);
        r.setExpiryYear(2030);
        r.setCurrency("GBP");
        r.setAmount(BigDecimal.valueOf(10000));
        r.setCvv("123");
        return r;
    }
}
