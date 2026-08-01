package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
import com.checkout.payment.gateway.logging.MessageLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@RequiredArgsConstructor
public class HttpBankClient implements IBankClient {

    private final RestClient restClient;

    @Override
    public BankPaymentResponse sendPayment(BankPaymentRequest request) {
        String last4 = request.getCardNumber().substring(request.getCardNumber().length() - 4);
        log.debug("Sending payment to bank for card ending {}", last4);

        long start = System.currentTimeMillis();
        try {
            BankPaymentResponse response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BankPaymentResponse.class);

            if (response == null) throw new BankCommunicationException("Bank returned null response");

            long durationMs = System.currentTimeMillis() - start;
            log.debug("Bank responded: authorized={}", response.isAuthorized());
            MessageLogger.logOutbound("POST", "/payments", "acquiring-bank", 200, durationMs, true);
            return response;

        } catch (RestClientResponseException ex) {
            long durationMs = System.currentTimeMillis() - start;
            int status = ex.getStatusCode().value();
            MessageLogger.logOutbound("POST", "/payments", "acquiring-bank", status, durationMs, false);
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                log.warn("Acquiring bank returned 503 Service Unavailable");
                throw new BankServiceUnavailableException("Acquiring bank returned 503", ex);
            }
            log.error("Acquiring bank returned error status {}", status);
            throw new BankServiceUnavailableException("Acquiring bank error: " + status, ex);

        } catch (ResourceAccessException ex) {
            long durationMs = System.currentTimeMillis() - start;
            MessageLogger.logOutbound("POST", "/payments", "acquiring-bank", null, durationMs, false);
            log.error("Acquiring bank unreachable", ex);
            throw new BankServiceUnavailableException("Acquiring bank unreachable", ex);
        }
    }
}
