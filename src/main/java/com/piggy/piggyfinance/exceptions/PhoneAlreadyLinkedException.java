package com.piggy.piggyfinance.exceptions;

public class PhoneAlreadyLinkedException extends RuntimeException {
    public PhoneAlreadyLinkedException(String message) {
        super(message);
    }
}