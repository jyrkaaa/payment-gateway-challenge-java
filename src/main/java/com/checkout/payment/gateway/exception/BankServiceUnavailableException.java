package com.checkout.payment.gateway.exception;

public class BankServiceUnavailableException extends BankCommunicationException {

    public BankServiceUnavailableException(String message) {
        super(message);
    }

    public BankServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
    
}
