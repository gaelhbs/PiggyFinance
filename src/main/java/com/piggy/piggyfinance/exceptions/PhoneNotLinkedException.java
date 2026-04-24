package com.piggy.piggyfinance.exceptions;

public class PhoneNotLinkedException extends RuntimeException {
    public PhoneNotLinkedException(String message) {
        super(message);
    }
}