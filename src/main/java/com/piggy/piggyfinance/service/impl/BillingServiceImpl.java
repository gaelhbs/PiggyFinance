package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.PhoneNotLinkedException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.PasswordResetToken;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.ActivateResponse;
import com.piggy.piggyfinance.model.responses.WhatsAppSubscriptionStatusResponse;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.BillingService;
import com.piggy.piggyfinance.service.EntitlementService;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillingServiceImpl implements BillingService {

    private final StripeGateway stripeGateway;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final StripeProperties stripeProperties;
    private final EntitlementService entitlementService;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Override
    @Transactional
    public String createCheckout(UUID userId, String priceAlias) {
        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("No subscription for user: " + userId));

        String priceId = stripeProperties.priceIdForAlias(priceAlias);
        if (priceId == null) {
            throw new BusinessException("Unknown plan: " + priceAlias);
        }

        if (subscription.getStatus() == SubscriptionStatus.ACTIVE
                && subscription.getSource() == SubscriptionSource.STRIPE) {
            throw new BusinessException("You already have an active subscription. Manage it in the billing portal.");
        }

        User user = subscription.getUser();
        String customerId = subscription.getStripeCustomerId();
        if (customerId == null) {
            customerId = stripeGateway.createCustomer(user.getEmail());
            subscriptionRepository.save(subscription.toBuilder().stripeCustomerId(customerId).build());
        }

        return stripeGateway.createCheckoutSession(
                customerId,
                userId.toString(),
                priceId,
                appBaseUrl + "/assinatura/sucesso?session_id={CHECKOUT_SESSION_ID}",
                appBaseUrl + "/assinatura/cancelado");
    }

    @Override
    public String createPortal(UUID userId) {
        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("No subscription for user: " + userId));
        if (subscription.getStripeCustomerId() == null) {
            throw new BusinessException("No billing account yet. Subscribe first.");
        }
        return stripeGateway.createPortalSession(
                subscription.getStripeCustomerId(), appBaseUrl + "/perfil");
    }

    @Override
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        StripeWebhookEvent event = stripeGateway.parseWebhookEvent(payload, signatureHeader);
        log.info("Processing Stripe webhook {} ({})", event.id(), event.type());

        switch (event.type()) {
            case "checkout.session.completed" -> applyCheckoutCompleted(event);
            case "customer.subscription.updated" -> applyStatusFromStripe(event);
            case "customer.subscription.deleted" -> applyCanceled(event);
            case "invoice.payment_failed" -> applyPastDue(event);
            default -> log.debug("Ignoring unhandled Stripe event type: {}", event.type());
        }
    }

    private void applyCheckoutCompleted(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;

        Subscription subscription = event.clientReferenceId() != null
                ? subscriptionRepository.findByUserId(UUID.fromString(event.clientReferenceId())).orElse(null)
                : subscriptionRepository.findByStripeCustomerId(sub.customerId()).orElse(null);

        if (subscription == null) {
            // Pre-account LP funnel backstop: create/resolve a provisional user by email,
            // then upsert their subscription. No setup token is ever issued from the webhook.
            if (event.customerEmail() == null) {
                log.warn("checkout.session.completed for unknown user and no email (event {})", event.id());
                return;
            }
            User user = userRepository.findByEmail(event.customerEmail()).orElseGet(() -> {
                String localPart = event.customerEmail().split("@")[0];
                return userRepository.save(User.builder()
                        .name(localPart)
                        .email(event.customerEmail())
                        .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                        .createdAt(LocalDateTime.now())
                        .provisional(true)
                        .build());
            });
            subscription = subscriptionRepository.findByUserId(user.getId())
                    .orElse(Subscription.builder().user(user).build());
        }

        subscriptionRepository.save(subscription.toBuilder()
                .tier(tierFor(sub))
                .status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.STRIPE)
                .stripeCustomerId(sub.customerId())
                .stripeSubscriptionId(sub.subscriptionId())
                .currentPeriodEnd(sub.currentPeriodEnd())
                .cancelAtPeriodEnd(sub.cancelAtPeriodEnd())
                .build());
    }

    private void applyStatusFromStripe(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;
        subscriptionRepository.findByStripeSubscriptionId(sub.subscriptionId()).ifPresent(s ->
                subscriptionRepository.save(s.toBuilder()
                        .tier(tierFor(sub))
                        .status(mapStatus(sub.status()))
                        .currentPeriodEnd(sub.currentPeriodEnd())
                        .cancelAtPeriodEnd(sub.cancelAtPeriodEnd())
                        .build()));
    }

    private void applyCanceled(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;
        subscriptionRepository.findByStripeSubscriptionId(sub.subscriptionId()).ifPresent(s ->
                subscriptionRepository.save(s.toBuilder().status(SubscriptionStatus.CANCELED).build()));
    }

    private void applyPastDue(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;
        subscriptionRepository.findByStripeSubscriptionId(sub.subscriptionId()).ifPresent(s ->
                subscriptionRepository.save(s.toBuilder().status(SubscriptionStatus.PAST_DUE).build()));
    }

    private com.piggy.piggyfinance.enums.SubscriptionTier tierFor(StripeSubscriptionData sub) {
        com.piggy.piggyfinance.enums.SubscriptionTier tier = stripeProperties.tierForPriceId(sub.priceId());
        return tier != null ? tier : com.piggy.piggyfinance.enums.SubscriptionTier.ESSENCIAL;
    }

    private SubscriptionStatus mapStatus(String stripeStatus) {
        if (stripeStatus == null) return SubscriptionStatus.PAST_DUE;
        return switch (stripeStatus) {
            case "active" -> SubscriptionStatus.ACTIVE;
            case "trialing" -> SubscriptionStatus.TRIALING;
            case "past_due" -> SubscriptionStatus.PAST_DUE;
            case "canceled", "unpaid", "incomplete_expired" -> SubscriptionStatus.CANCELED;
            default -> SubscriptionStatus.PAST_DUE;
        };
    }

    private static final int SETUP_TOKEN_EXPIRY_MINUTES = 30;

    @Override
    @Transactional
    public ActivateResponse activate(String sessionId) {
        StripeCheckoutData checkout = stripeGateway.retrieveCheckoutSession(sessionId);
        if (!checkout.paid()) {
            throw new BusinessException("Checkout session is not paid");
        }
        if (checkout.customerEmail() == null) {
            throw new BusinessException("Checkout session has no email");
        }

        StripeSubscriptionData sub = stripeGateway.retrieveSubscription(checkout.subscriptionId());
        var tier = tierFor(sub);

        User user = userRepository.findByEmail(checkout.customerEmail()).orElseGet(() -> {
            String localPart = checkout.customerEmail().split("@")[0];
            return userRepository.save(User.builder()
                    .name(localPart)
                    .email(checkout.customerEmail())
                    .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .createdAt(LocalDateTime.now())
                    .provisional(true)
                    .build());
        });

        Subscription existing = subscriptionRepository.findByUserId(user.getId()).orElse(null);
        Subscription.SubscriptionBuilder builder = existing != null
                ? existing.toBuilder()
                : Subscription.builder().user(user);

        subscriptionRepository.save(builder
                .tier(tier)
                .status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.STRIPE)
                .stripeCustomerId(checkout.customerId())
                .stripeSubscriptionId(checkout.subscriptionId())
                .currentPeriodEnd(sub.currentPeriodEnd())
                .cancelAtPeriodEnd(sub.cancelAtPeriodEnd())
                .build());

        if (!user.isProvisional()) {
            log.info("Linked subscription to existing account {} via LP (no setup token issued)", user.getId());
            return new ActivateResponse(null, user.getEmail());
        }

        passwordResetTokenRepository.markAllUnusedByUserIdAsUsed(user.getId());
        String setupToken = UUID.randomUUID().toString();
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .token(setupToken)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(SETUP_TOKEN_EXPIRY_MINUTES))
                .used(false)
                .build());

        log.info("Activated subscription via LP for provisional user {}", user.getId());
        return new ActivateResponse(setupToken, user.getEmail());
    }

    @Override
    public WhatsAppSubscriptionStatusResponse getStatusByPhone(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new PhoneNotLinkedException(
                        "No account linked to this phone number. Please link your WhatsApp in the app."));
        entitlementService.requireTier(user.getId(), SubscriptionTier.PRO);

        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseThrow(() -> new UserNotFoundException("No subscription for user: " + user.getId()));

        return new WhatsAppSubscriptionStatusResponse(
                subscription.getTier(),
                subscription.getStatus(),
                subscription.getCurrentPeriodEnd(),
                subscription.isCancelAtPeriodEnd()
        );
    }
}
