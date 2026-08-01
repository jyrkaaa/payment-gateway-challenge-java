package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
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
        try {
            log.debug("Sending payment to bank for card ending {}", request.getCardNumber().substring(request.getCardNumber().length() - 4));
            BankPaymentResponse response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BankPaymentResponse.class);

            if (response == null) throw new BankCommunicationException("Bank returned null response");

            log.debug("Bank responded: authorized={}", response.isAuthorized());
            return response;

        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                log.warn("Acquiring bank returned 503 Service Unavailable");
                throw new BankServiceUnavailableException("Acquiring bank returned 503", ex);
            }
            log.error("Acquiring bank returned error status {}", status);
            throw new BankServiceUnavailableException("Acquiring bank error: " + status, ex);
        } catch (ResourceAccessException ex) {
            log.error("Acquiring bank unreachable", ex);
            throw new BankServiceUnavailableException("Acquiring bank unreachable", ex);
        }
    }
}
