package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.exception.IdempotencyKeyReuseException;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryIdempotencyStoreTest {

    private InMemoryIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
    }

    @Test
    void firstCall_executesSupplierAndReturnsNotReplayed() {
        AtomicInteger calls = new AtomicInteger(0);
        PostPaymentResponse response = response();

        IdempotencyResult result = store.computeIfAbsent("key-1", "fp-a", () -> {
            calls.incrementAndGet();
            return response;
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.replayed()).isFalse();
        assertThat(result.response()).isSameAs(response);
    }

    @Test
    void secondCallSameKeyAndFingerprint_returnsReplayedWithoutCallingSupplier() {
        PostPaymentResponse response = response();
        store.computeIfAbsent("key-1", "fp-a", () -> response);

        AtomicInteger calls = new AtomicInteger(0);
        IdempotencyResult replayed = store.computeIfAbsent("key-1", "fp-a", () -> {
            calls.incrementAndGet();
            return response();
        });

        assertThat(calls.get()).isEqualTo(0);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.response()).isSameAs(response);
    }

    @Test
    void secondCallSameKeyDifferentFingerprint_throwsIdempotencyKeyReuseException() {
        store.computeIfAbsent("key-1", "fp-a", this::response);

        assertThatThrownBy(() -> store.computeIfAbsent("key-1", "fp-b", this::response))
            .isInstanceOf(IdempotencyKeyReuseException.class)
            .hasMessageContaining("key-1");
    }

    @Test
    void differentKeys_areIndependent() {
        PostPaymentResponse r1 = response();
        PostPaymentResponse r2 = response();

        IdempotencyResult res1 = store.computeIfAbsent("key-a", "fp-a", () -> r1);
        IdempotencyResult res2 = store.computeIfAbsent("key-b", "fp-b", () -> r2);

        assertThat(res1.response()).isSameAs(r1);
        assertThat(res2.response()).isSameAs(r2);
        assertThat(res1.replayed()).isFalse();
        assertThat(res2.replayed()).isFalse();
    }

    private PostPaymentResponse response() {
        PostPaymentResponse r = new PostPaymentResponse();
        r.setId(UUID.randomUUID());
        return r;
    }
}
