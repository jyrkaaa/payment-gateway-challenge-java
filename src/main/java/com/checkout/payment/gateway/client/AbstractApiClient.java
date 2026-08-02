package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.logging.MessageLogger;

public abstract class AbstractApiClient {

    protected String TargetServiceName;

    protected <T> T executeWithLogging(String method, String endpoint, String requestBody,
                                       RemoteOperation<T> operation) {
        long start = System.currentTimeMillis();
        try {
            CallResult<T> result = operation.execute();
            long durationMs = System.currentTimeMillis() - start;
            MessageLogger.logOutbound(method, endpoint, TargetServiceName,
                result.statusCode(), durationMs, true, requestBody, result.responseBody());
            return result.value();
        } catch (CallException e) {
            long durationMs = System.currentTimeMillis() - start;
            MessageLogger.logOutbound(method, endpoint, TargetServiceName,
                e.getStatusCode(), durationMs, false, requestBody, e.getResponseBody());
            throw e.getCause();
        }
    }

    protected record CallResult<T>(T value, Integer statusCode, String responseBody) {}

    protected static class CallException extends Exception {

        private final Integer statusCode;
        private final String responseBody;
        private final RuntimeException domainException;

        protected CallException(Integer statusCode, String responseBody, RuntimeException domainException) {
            super(domainException);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.domainException = domainException;
        }

        public Integer getStatusCode() { return statusCode; }
        public String getResponseBody() { return responseBody; }

        @Override
        public RuntimeException getCause() { return domainException; }
    }

    @FunctionalInterface
    protected interface RemoteOperation<T> {
        CallResult<T> execute() throws CallException;
    }
}
