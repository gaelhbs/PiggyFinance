package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.SubscriptionTier;

import java.util.UUID;

public interface EntitlementService {
    SubscriptionTier getEffectiveTier(UUID userId);
    boolean hasAtLeast(UUID userId, SubscriptionTier minimum);
    void requireTier(UUID userId, SubscriptionTier minimum);
}
