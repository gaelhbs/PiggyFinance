package com.piggy.piggyfinance.service.stripe;

import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;

public interface StripeGateway {
    String createCustomer(String email);
    String createCheckoutSession(String customerId, String userId, String priceId,
                                 String successUrl, String cancelUrl);
    String createPortalSession(String customerId, String returnUrl);
    StripeCheckoutData retrieveCheckoutSession(String sessionId);
    StripeSubscriptionData retrieveSubscription(String subscriptionId);
    StripeWebhookEvent parseWebhookEvent(String payload, String signatureHeader);
}
