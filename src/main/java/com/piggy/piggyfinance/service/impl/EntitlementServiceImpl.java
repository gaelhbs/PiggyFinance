package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementServiceImpl implements EntitlementService {

    private final SubscriptionRepository subscriptionRepository;

    @Override
    public SubscriptionTier getEffectiveTier(UUID userId) {
        return subscriptionRepository.findByUserId(userId)
                .map(this::resolve)
                .orElse(SubscriptionTier.FREE);
    }

    private SubscriptionTier resolve(Subscription s) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return switch (s.getStatus()) {
            case TRIALING -> (s.getTrialEndsAt() != null && s.getTrialEndsAt().isAfter(now))
                    ? s.getTier() : SubscriptionTier.FREE;
            case ACTIVE -> (s.getCurrentPeriodEnd() == null || s.getCurrentPeriodEnd().isAfter(now))
                    ? s.getTier() : SubscriptionTier.FREE;
            case PAST_DUE -> (s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isAfter(now))
                    ? s.getTier() : SubscriptionTier.FREE;
            default -> SubscriptionTier.FREE;
        };
    }

    @Override
    public boolean hasAtLeast(UUID userId, SubscriptionTier minimum) {
        return getEffectiveTier(userId).ordinal() >= minimum.ordinal();
    }

    @Override
    public void requireTier(UUID userId, SubscriptionTier minimum) {
        if (!hasAtLeast(userId, minimum)) {
            throw new FeatureLockedException(
                    "This feature requires the " + minimum + " plan", minimum);
        }
    }
}
