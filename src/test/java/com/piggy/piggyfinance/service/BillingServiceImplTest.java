package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.BillingServiceImpl;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingServiceImplTest {

    @Mock StripeGateway stripeGateway;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock StripeProperties stripeProperties;
    @InjectMocks BillingServiceImpl service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder().id(userId).name("Gab").email("gab@test.com")
                .password("hash").createdAt(LocalDateTime.now()).build();
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://piggyfinance.cloud");
    }

    private Subscription trialSub() {
        return Subscription.builder()
                .user(user).tier(SubscriptionTier.PRO).status(SubscriptionStatus.TRIALING)
                .source(SubscriptionSource.INTERNAL)
                .trialEndsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(3))
                .build();
    }

    @Test
    void createCheckout_createsCustomerAndReturnsUrl() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        when(stripeProperties.priceIdForAlias("pro-monthly")).thenReturn("price_123");
        when(stripeGateway.createCustomer("gab@test.com")).thenReturn("cus_1");
        when(stripeGateway.createCheckoutSession(eq("cus_1"), eq(userId.toString()), eq("price_123"),
                any(), any())).thenReturn("https://checkout.stripe.com/x");

        String url = service.createCheckout(userId, "pro-monthly");

        assertThat(url).isEqualTo("https://checkout.stripe.com/x");
    }

    @Test
    void createCheckout_unknownAlias_throwsBusinessException() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        when(stripeProperties.priceIdForAlias("bogus")).thenReturn(null);

        assertThatThrownBy(() -> service.createCheckout(userId, "bogus"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createCheckout_alreadyActiveStripeSub_throwsBusinessException() {
        Subscription active = Subscription.builder()
                .user(user).tier(SubscriptionTier.PRO).status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.STRIPE).stripeCustomerId("cus_1")
                .currentPeriodEnd(OffsetDateTime.now(ZoneOffset.UTC).plusDays(20)).build();
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(active));
        when(stripeProperties.priceIdForAlias("pro-monthly")).thenReturn("price_123");

        assertThatThrownBy(() -> service.createCheckout(userId, "pro-monthly"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPortal_noCustomer_throwsBusinessException() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        assertThatThrownBy(() -> service.createPortal(userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPortal_withCustomer_returnsUrl() {
        Subscription withCustomer = trialSub().toBuilder().stripeCustomerId("cus_9").build();
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(withCustomer));
        when(stripeGateway.createPortalSession(eq("cus_9"), any())).thenReturn("https://portal/x");

        assertThat(service.createPortal(userId)).isEqualTo("https://portal/x");
    }
}
