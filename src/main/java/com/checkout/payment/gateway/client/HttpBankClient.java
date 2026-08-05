package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Slf4j
public class HttpBankClient extends AbstractRestClient implements IBankClient {

    private static final String ENDPOINT = "/payments";
    public String TargetServiceName() { return "acquiring-bank"; }
    
    public HttpBankClient(RestClient restClient) {
        super(restClient);
    }

    public BankPaymentResponse sendPayment(BankPaymentRequest request) {
        log.debug("Sending payment to bank for card ending {}", request.getCardNumber());

        BankPaymentResponse response = post(ENDPOINT, request, BankPaymentResponse.class);
        if (response == null) throw new BankCommunicationException("Bank returned null response");
        log.debug("Bank responded: authorized={}", response.isAuthorized());
        return response;
    }

    @Override
    protected RuntimeException mapHttpError(HttpStatusCode statusCode, String responseBody) {
        if (statusCode.is5xxServerError()) {
            return new BankServiceUnavailableException("Acquiring bank returned " + statusCode);
        }
        return new BankCommunicationException("Acquiring bank returned " + statusCode);
    }

    @Override
    protected RuntimeException mapNetworkError(ResourceAccessException ex) {
        return new BankServiceUnavailableException("Acquiring bank unreachable", ex);
    }
}
