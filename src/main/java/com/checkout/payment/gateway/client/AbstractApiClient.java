package com.checkout.payment.gateway.client;

import org.springframework.http.HttpStatusCode;

import com.checkout.payment.gateway.logging.MessageLogger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractApiClient {

    protected abstract String TargetServiceName();

    protected <T> T executeWithLogging(String method, String endpoint, String requestBody,
                                       RemoteOperation<T> operation) {
        long start = System.currentTimeMillis();
        try {
            CallResult<T> result = operation.execute();
            long durationMs = System.currentTimeMillis() - start;
            MessageLogger.logOutbound(method, endpoint, TargetServiceName(),
                result.statusCode().value(), durationMs, true, requestBody, result.responseBody());
            return result.value();
        } catch (CallException e) {
            long durationMs = System.currentTimeMillis() - start;
            int statusCode = e.getStatusCode() != null ? e.getStatusCode().value() : 500;
            log.warn("StatusCode from e: {}", statusCode, e);
            MessageLogger.logOutbound(method, endpoint, TargetServiceName(),
                statusCode, durationMs, false, requestBody, e.getResponseBody());
            throw e.getCause();
        }
    }

    protected record CallResult<T>(T value, HttpStatusCode statusCode, String responseBody) {}

    protected static class CallException extends Exception {

        private final HttpStatusCode statusCode;
        private final String responseBody;
        private final RuntimeException domainException;

        protected CallException(HttpStatusCode statusCode, String responseBody, RuntimeException domainException) {
            super(domainException);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.domainException = domainException;
        }

        public HttpStatusCode getStatusCode() { return statusCode; }
        public String getResponseBody() { return responseBody; }

        @Override
        public RuntimeException getCause() { return domainException; }
    }

    @FunctionalInterface
    protected interface RemoteOperation<T> {
        CallResult<T> execute() throws CallException;
    }
}
