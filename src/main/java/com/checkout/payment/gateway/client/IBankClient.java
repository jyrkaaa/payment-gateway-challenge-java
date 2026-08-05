package com.checkout.payment.gateway.client;

public interface IBankClient {

    /**
     * Submits a payment to the acquiring bank.
     *
     * @throws com.checkout.payment.gateway.exception.BankServiceUnavailableException on 5xx or network failure
     * @throws com.checkout.payment.gateway.exception.BankCommunicationException on 4xx or other unexpected errors
     */
    BankPaymentResponse sendPayment(BankPaymentRequest request);
}
