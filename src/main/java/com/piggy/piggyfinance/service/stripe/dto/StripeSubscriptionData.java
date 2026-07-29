package com.piggy.piggyfinance.service.stripe.dto;

import java.time.OffsetDateTime;

public record StripeSubscriptionData(
        String subscriptionId,
        String customerId,
        String priceId,
        String status,
        OffsetDateTime currentPeriodEnd,
        boolean cancelAtPeriodEnd
) {}
