package com.piggy.piggyfinance.exceptions;

public class WhatsAppTransactionMismatchException extends RuntimeException {
    public WhatsAppTransactionMismatchException(String message) {
        super(message);
    }
}
