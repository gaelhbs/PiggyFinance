package com.piggy.piggyfinance.job;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionExpiryJobTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @InjectMocks SubscriptionExpiryJob job;
    @Captor ArgumentCaptor<Subscription> captor;

    @Test
    void expireStale_flipsExpiredToFree() {
        Subscription expiredTrial = Subscription.builder()
                .tier(SubscriptionTier.PRO).status(SubscriptionStatus.TRIALING)
                .source(SubscriptionSource.INTERNAL)
                .trialEndsAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)).build();
        when(subscriptionRepository.findExpired(any())).thenReturn(List.of(expiredTrial));

        job.expireStale();

        verify(subscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(captor.getValue().getTier()).isEqualTo(SubscriptionTier.FREE);
    }
}
