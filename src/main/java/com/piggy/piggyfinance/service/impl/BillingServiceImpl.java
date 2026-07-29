package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.ActivateResponse;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.BillingService;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        throw new UnsupportedOperationException("Implemented in Task 8");
    }

    @Override
    @Transactional
    public ActivateResponse activate(String sessionId) {
        throw new UnsupportedOperationException("Implemented in Task 9");
    }
}
