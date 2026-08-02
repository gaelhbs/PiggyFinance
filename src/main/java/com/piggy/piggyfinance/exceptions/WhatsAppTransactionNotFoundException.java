package com.piggy.piggyfinance.exceptions;

public class WhatsAppTransactionNotFoundException extends RuntimeException {
    public WhatsAppTransactionNotFoundException(String message) {
        super(message);
    }
}
