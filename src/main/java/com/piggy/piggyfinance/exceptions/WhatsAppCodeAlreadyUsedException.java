package com.piggy.piggyfinance.exceptions;

public class WhatsAppCodeAlreadyUsedException extends RuntimeException {
    public WhatsAppCodeAlreadyUsedException(String message) {
        super(message);
    }
}