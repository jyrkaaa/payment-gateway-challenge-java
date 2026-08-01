package com.checkout.payment.gateway.client;

public interface IBankClient {

    /**
     * Submits a payment to the acquiring bank.
     *
     * @throws com.checkout.payment.gateway.exception.BankServiceUnavailableException on 503 or network failure
     * @throws com.checkout.payment.gateway.exception.BankCommunicationException on unexpected errors
     */
    BankPaymentResponse sendPayment(BankPaymentRequest request);
}
