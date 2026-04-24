package com.piggy.piggyfinance.exceptions;

public class WhatsAppCodeExpiredException extends RuntimeException {
    public WhatsAppCodeExpiredException(String message) {
        super(message);
    }
}