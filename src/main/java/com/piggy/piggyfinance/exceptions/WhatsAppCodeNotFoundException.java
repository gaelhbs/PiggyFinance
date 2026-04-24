package com.piggy.piggyfinance.exceptions;

public class WhatsAppCodeNotFoundException extends RuntimeException {
    public WhatsAppCodeNotFoundException(String message) {
        super(message);
    }
}
