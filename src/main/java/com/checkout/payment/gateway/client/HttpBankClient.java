package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
public class HttpBankClient extends AbstractRestClient implements IBankClient {

    private static final String ENDPOINT = "/payments";
    public String TargetServiceName = "acquiring-bank";
    
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
    protected RuntimeException mapHttpError(int statusCode, RestClientResponseException ex) {
        if (statusCode == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            log.warn("Acquiring bank returned 503 Service Unavailable");
            return new BankServiceUnavailableException("Acquiring bank returned 503", ex);
        }
        log.error("Acquiring bank returned error status {}", statusCode);
        return new BankServiceUnavailableException("Acquiring bank error: " + statusCode, ex);
    }

    @Override
    protected RuntimeException mapNetworkError(ResourceAccessException ex) {
        log.error("Acquiring bank unreachable", ex);
        return new BankServiceUnavailableException("Acquiring bank unreachable", ex);
    }
}
