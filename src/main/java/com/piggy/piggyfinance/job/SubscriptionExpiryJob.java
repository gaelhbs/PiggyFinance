package com.piggy.piggyfinance.job;

import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryJob {

    private final SubscriptionRepository subscriptionRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void expireStale() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Subscription s : subscriptionRepository.findExpired(now)) {
            subscriptionRepository.save(s.toBuilder()
                    .status(SubscriptionStatus.EXPIRED)
                    .tier(SubscriptionTier.FREE)
                    .build());
            log.info("Expired subscription {} downgraded to FREE", s.getId());
        }
    }
}
