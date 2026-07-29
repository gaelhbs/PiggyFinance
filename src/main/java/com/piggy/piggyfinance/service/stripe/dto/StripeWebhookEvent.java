package com.piggy.piggyfinance.service.stripe.dto;

public record StripeWebhookEvent(
        String id,
        String type,
        String clientReferenceId,
        String customerEmail,
        StripeSubscriptionData subscription
) {}
