package com.piggy.piggyfinance.model.responses;

import com.piggy.piggyfinance.enums.SubscriptionTier;

import java.time.LocalDateTime;

public record FeatureLockedResponse(
        String code,
        String message,
        String requiredTier,
        LocalDateTime timestamp
) {
    public static FeatureLockedResponse of(String message, SubscriptionTier requiredTier) {
        return new FeatureLockedResponse("FEATURE_LOCKED", message, requiredTier.name(), LocalDateTime.now());
    }
}
