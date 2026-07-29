package com.piggy.piggyfinance.repository;

import com.piggy.piggyfinance.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByUserId(UUID userId);

    Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    @Query("""
        SELECT s FROM Subscription s
        WHERE (s.status = com.piggy.piggyfinance.enums.SubscriptionStatus.TRIALING AND s.trialEndsAt < :now)
           OR (s.status IN (com.piggy.piggyfinance.enums.SubscriptionStatus.ACTIVE,
                            com.piggy.piggyfinance.enums.SubscriptionStatus.PAST_DUE)
               AND s.currentPeriodEnd < :now)
        """)
    List<Subscription> findExpired(@Param("now") OffsetDateTime now);
}
