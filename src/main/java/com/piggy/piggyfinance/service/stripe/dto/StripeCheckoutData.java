package com.piggy.piggyfinance.service.stripe.dto;

public record StripeCheckoutData(
        String sessionId,
        String customerId,
        String subscriptionId,
        String clientReferenceId,
        String customerEmail,
        boolean paid
) {}
