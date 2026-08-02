package com.checkout.payment.gateway.client;

import org.springframework.http.HttpStatusCode;

public interface RestApiResponse {
    HttpStatusCode getStatusCode();
    void setStatusCode(HttpStatusCode statusCode);
}
