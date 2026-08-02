package com.piggy.piggyfinance.model.responses;

import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;

import java.time.OffsetDateTime;

public record WhatsAppSubscriptionStatusResponse(
        SubscriptionTier tier,
        SubscriptionStatus status,
        OffsetDateTime currentPeriodEnd,
        boolean cancelAtPeriodEnd
) {}
