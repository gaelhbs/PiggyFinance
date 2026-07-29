package com.piggy.piggyfinance.exceptions;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import lombok.Getter;

@Getter
public class FeatureLockedException extends RuntimeException {
    private final SubscriptionTier requiredTier;

    public FeatureLockedException(String message, SubscriptionTier requiredTier) {
        super(message);
        this.requiredTier = requiredTier;
    }
}
