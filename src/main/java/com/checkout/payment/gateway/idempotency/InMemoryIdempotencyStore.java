package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.exception.IdempotencyKeyReuseException;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class InMemoryIdempotencyStore implements IIdempotencyStore {

    private record PendingEntry(String fingerprint, CompletableFuture<PostPaymentResponse> future) {
    }

    private final ConcurrentHashMap<String, PendingEntry> records = new ConcurrentHashMap<>();

    @Override
    public IdempotencyResult computeIfAbsent(String key, String fingerprint,
        Supplier<PostPaymentResponse> supplier) {
        AtomicBoolean computed = new AtomicBoolean(false);

        PendingEntry entry = records.computeIfAbsent(key, k -> {
            computed.set(true);
            return new PendingEntry(fingerprint, new CompletableFuture<>());
        });

        if (!entry.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReuseException(key);
        }

        if (computed.get()) {
            try {
                entry.future().complete(supplier.get());
            } catch (RuntimeException ex) {
                entry.future().completeExceptionally(ex);
                records.remove(key, entry);
                throw ex;
            }
        }

        return new IdempotencyResult(join(entry.future()), !computed.get());
    }

    private static PostPaymentResponse join(CompletableFuture<PostPaymentResponse> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }
}
