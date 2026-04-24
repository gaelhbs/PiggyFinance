package com.piggy.piggyfinance.exceptions;

public class AccountAlreadyLinkedException extends RuntimeException {
    public AccountAlreadyLinkedException(String message) {
        super(message);
    }
}