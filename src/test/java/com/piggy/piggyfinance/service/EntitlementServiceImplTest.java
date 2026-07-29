package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.service.impl.EntitlementServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceImplTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @InjectMocks EntitlementServiceImpl service;

    private final UUID userId = UUID.randomUUID();

    private Subscription sub(SubscriptionTier tier, SubscriptionStatus status,
                            OffsetDateTime trialEndsAt, OffsetDateTime periodEnd) {
        return Subscription.builder()
                .tier(tier).status(status).source(SubscriptionSource.INTERNAL)
                .trialEndsAt(trialEndsAt).currentPeriodEnd(periodEnd)
                .build();
    }

    private OffsetDateTime future() { return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1); }
    private OffsetDateTime past()   { return OffsetDateTime.now(ZoneOffset.UTC).minusDays(1); }

    @Test
    void noSubscription_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.empty());
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void trialingWithinWindow_resolvesTierPro() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.TRIALING, future(), null)));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void trialingExpired_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.TRIALING, past(), null)));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void activeWithFuturePeriod_resolvesTier() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.ESSENCIAL, SubscriptionStatus.ACTIVE, null, future())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.ESSENCIAL);
    }

    @Test
    void activeExpiredPeriod_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.ACTIVE, null, past())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void pastDueWithinPeriod_stillResolvesTier() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.PAST_DUE, null, future())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void canceled_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.CANCELED, null, future())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void hasAtLeast_comparesByOrder() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.ESSENCIAL, SubscriptionStatus.ACTIVE, null, future())));
        assertThat(service.hasAtLeast(userId, SubscriptionTier.ESSENCIAL)).isTrue();
        assertThat(service.hasAtLeast(userId, SubscriptionTier.PRO)).isFalse();
        assertThat(service.hasAtLeast(userId, SubscriptionTier.FREE)).isTrue();
    }

    @Test
    void requireTier_throwsWhenBelow() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.FREE, SubscriptionStatus.ACTIVE, null, future())));
        assertThatThrownBy(() -> service.requireTier(userId, SubscriptionTier.PRO))
                .isInstanceOf(FeatureLockedException.class)
                .satisfies(ex -> assertThat(((FeatureLockedException) ex).getRequiredTier())
                        .isEqualTo(SubscriptionTier.PRO));
    }

    @Test
    void requireTier_passesWhenMet() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.ACTIVE, null, future())));
        assertThatCode(() -> service.requireTier(userId, SubscriptionTier.PRO)).doesNotThrowAnyException();
    }
}
