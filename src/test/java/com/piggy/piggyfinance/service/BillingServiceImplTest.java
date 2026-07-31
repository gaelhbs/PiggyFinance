package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.PasswordResetToken;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.ActivateResponse;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.BillingServiceImpl;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Captor ArgumentCaptor<Subscription> subCaptor;

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

    @Test
    void webhook_checkoutCompleted_activatesSubscriptionForUser() {
        StripeSubscriptionData sub = new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false);
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_1", "checkout.session.completed", userId.toString(), "gab@test.com", sub);
        when(stripeGateway.parseWebhookEvent("payload", "sig")).thenReturn(event);
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);

        service.handleWebhook("payload", "sig");

        verify(subscriptionRepository).save(subCaptor.capture());
        Subscription saved = subCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.getTier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(saved.getSource()).isEqualTo(SubscriptionSource.STRIPE);
        assertThat(saved.getStripeSubscriptionId()).isEqualTo("sub_1");
    }

    @Test
    void webhook_subscriptionUpdatedPastDue_setsPastDue() {
        StripeSubscriptionData sub = new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "past_due",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(5), false);
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_2", "customer.subscription.updated", null, null, sub);
        when(stripeGateway.parseWebhookEvent("p", "s")).thenReturn(event);
        Subscription existing = trialSub().toBuilder()
                .status(SubscriptionStatus.ACTIVE).source(SubscriptionSource.STRIPE)
                .stripeSubscriptionId("sub_1").build();
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(existing));
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);

        service.handleWebhook("p", "s");

        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void webhook_subscriptionDeleted_setsCanceled() {
        StripeSubscriptionData sub = new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "canceled", null, false);
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_3", "customer.subscription.deleted", null, null, sub);
        when(stripeGateway.parseWebhookEvent("p", "s")).thenReturn(event);
        Subscription existing = trialSub().toBuilder()
                .status(SubscriptionStatus.ACTIVE).source(SubscriptionSource.STRIPE)
                .stripeSubscriptionId("sub_1").build();
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(existing));

        service.handleWebhook("p", "s");

        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    void webhook_unhandledType_isIgnored() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_4", "customer.created", null, null, null);
        when(stripeGateway.parseWebhookEvent("p", "s")).thenReturn(event);

        service.handleWebhook("p", "s");

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void activate_newEmail_createsProvisionalUserAndReturnsToken() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_1", "cus_1", "sub_1", null, "novo@test.com", true);
        when(stripeGateway.retrieveCheckoutSession("cs_1")).thenReturn(checkout);
        when(stripeGateway.retrieveSubscription("sub_1")).thenReturn(new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false));
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);
        when(userRepository.findByEmail("novo@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("randomhash");
        User createdUser = User.builder().id(UUID.randomUUID()).name("novo").email("novo@test.com")
                .password("randomhash").createdAt(LocalDateTime.now()).provisional(true).build();
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(userCaptor.capture())).thenReturn(createdUser);
        when(subscriptionRepository.findByUserId(createdUser.getId())).thenReturn(Optional.empty());

        ActivateResponse resp = service.activate("cs_1");

        assertThat(resp.email()).isEqualTo("novo@test.com");
        assertThat(resp.setupToken()).isNotBlank();
        assertThat(userCaptor.getValue().isProvisional()).isTrue();
        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subCaptor.getValue().getTier()).isEqualTo(SubscriptionTier.PRO);
        verify(passwordResetTokenRepository).markAllUnusedByUserIdAsUsed(createdUser.getId());
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        InOrder inOrder = inOrder(passwordResetTokenRepository);
        inOrder.verify(passwordResetTokenRepository).markAllUnusedByUserIdAsUsed(createdUser.getId());
        inOrder.verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void activate_existingProvisionalAccount_returnsToken() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_5", "cus_5", "sub_5", null, "provisional@test.com", true);
        when(stripeGateway.retrieveCheckoutSession("cs_5")).thenReturn(checkout);
        when(stripeGateway.retrieveSubscription("sub_5")).thenReturn(new StripeSubscriptionData(
                "sub_5", "cus_5", "price_pro", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false));
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);
        UUID provisionalUserId = UUID.randomUUID();
        User provisionalUser = User.builder().id(provisionalUserId).name("provisional")
                .email("provisional@test.com").password("hash")
                .createdAt(LocalDateTime.now()).provisional(true).build();
        when(userRepository.findByEmail("provisional@test.com")).thenReturn(Optional.of(provisionalUser));
        when(subscriptionRepository.findByUserId(provisionalUserId)).thenReturn(Optional.empty());

        ActivateResponse resp = service.activate("cs_5");

        assertThat(resp.setupToken()).isNotBlank();
        verify(userRepository, never()).save(any(User.class));
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void activate_unpaidSession_throwsBusinessException() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_2", "cus_2", "sub_2", null, "x@test.com", false);
        when(stripeGateway.retrieveCheckoutSession("cs_2")).thenReturn(checkout);

        assertThatThrownBy(() -> service.activate("cs_2"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void activate_nullCustomerEmail_throwsBusinessException() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_4", "cus_4", "sub_4", null, null, true);
        when(stripeGateway.retrieveCheckoutSession("cs_4")).thenReturn(checkout);

        assertThatThrownBy(() -> service.activate("cs_4"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void activate_existingRealAccount_linksSubscriptionAndReturnsNoToken() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_3", "cus_3", "sub_3", null, "gab@test.com", true);
        when(stripeGateway.retrieveCheckoutSession("cs_3")).thenReturn(checkout);
        when(stripeGateway.retrieveSubscription("sub_3")).thenReturn(new StripeSubscriptionData(
                "sub_3", "cus_3", "price_ess", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false));
        when(stripeProperties.tierForPriceId("price_ess")).thenReturn(SubscriptionTier.ESSENCIAL);
        when(userRepository.findByEmail("gab@test.com")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));

        ActivateResponse resp = service.activate("cs_3");

        assertThat(resp.email()).isEqualTo("gab@test.com");
        assertThat(resp.setupToken()).isNull();
        verify(userRepository, never()).save(any(User.class));
        verify(passwordResetTokenRepository, never()).save(any());
        verify(passwordResetTokenRepository, never()).markAllUnusedByUserIdAsUsed(any());
        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getTier()).isEqualTo(SubscriptionTier.ESSENCIAL);
    }

    @Test
    void webhook_checkoutCompleted_unknownUserWithEmail_createsProvisionalUserAndSubscription() {
        StripeSubscriptionData sub = new StripeSubscriptionData(
                "sub_9", "cus_9", "price_pro", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false);
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_9", "checkout.session.completed", null, "orphan@test.com", sub);
        when(stripeGateway.parseWebhookEvent("payload", "sig")).thenReturn(event);
        when(subscriptionRepository.findByStripeCustomerId("cus_9")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("orphan@test.com")).thenReturn(Optional.empty());
        UUID orphanUserId = UUID.randomUUID();
        User orphanUser = User.builder().id(orphanUserId).name("orphan").email("orphan@test.com")
                .password("hash").createdAt(LocalDateTime.now()).provisional(true).build();
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(userCaptor.capture())).thenReturn(orphanUser);
        when(subscriptionRepository.findByUserId(orphanUserId)).thenReturn(Optional.empty());
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);

        service.handleWebhook("payload", "sig");

        assertThat(userCaptor.getValue().isProvisional()).isTrue();
        verify(subscriptionRepository).save(subCaptor.capture());
        Subscription saved = subCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.getSource()).isEqualTo(SubscriptionSource.STRIPE);
        verify(passwordResetTokenRepository, never()).save(any());
    }
}
