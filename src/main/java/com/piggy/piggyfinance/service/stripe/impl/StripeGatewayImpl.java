package com.piggy.piggyfinance.service.stripe.impl;

import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripeGatewayImpl implements StripeGateway {

    private final StripeProperties properties;

    @Override
    public String createCustomer(String email) {
        try {
            Customer customer = Customer.create(
                    CustomerCreateParams.builder().setEmail(email).build());
            return customer.getId();
        } catch (StripeException e) {
            throw new BusinessException("Failed to create Stripe customer: " + e.getMessage());
        }
    }

    @Override
    public String createCheckoutSession(String customerId, String userId, String priceId,
                                        String successUrl, String cancelUrl) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customerId)
                    .setClientReferenceId(userId)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(priceId).setQuantity(1L).build())
                    .build();
            return Session.create(params).getUrl();
        } catch (StripeException e) {
            throw new BusinessException("Failed to create checkout session: " + e.getMessage());
        }
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        try {
            com.stripe.param.billingportal.SessionCreateParams params =
                    com.stripe.param.billingportal.SessionCreateParams.builder()
                            .setCustomer(customerId)
                            .setReturnUrl(returnUrl)
                            .build();
            return com.stripe.model.billingportal.Session.create(params).getUrl();
        } catch (StripeException e) {
            throw new BusinessException("Failed to create billing portal session: " + e.getMessage());
        }
    }

    @Override
    public StripeCheckoutData retrieveCheckoutSession(String sessionId) {
        try {
            Session session = Session.retrieve(sessionId);
            boolean paid = "paid".equals(session.getPaymentStatus())
                    || "complete".equals(session.getStatus());
            String email = session.getCustomerEmail();
            if (email == null && session.getCustomerDetails() != null) {
                email = session.getCustomerDetails().getEmail();
            }
            return new StripeCheckoutData(
                    session.getId(),
                    session.getCustomer(),
                    session.getSubscription(),
                    session.getClientReferenceId(),
                    email,
                    paid);
        } catch (StripeException e) {
            throw new BusinessException("Failed to retrieve checkout session: " + e.getMessage());
        }
    }

    @Override
    public StripeSubscriptionData retrieveSubscription(String subscriptionId) {
        try {
            Subscription s = Subscription.retrieve(subscriptionId);
            return toSubscriptionData(s);
        } catch (StripeException e) {
            throw new BusinessException("Failed to retrieve subscription: " + e.getMessage());
        }
    }

    @Override
    public StripeWebhookEvent parseWebhookEvent(String payload, String signatureHeader) {
        try {
            Event event = Webhook.constructEvent(payload, signatureHeader, properties.getWebhookSecret());
            StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
            String type = event.getType();

            return switch (type) {
                case "checkout.session.completed" -> {
                    Session session = (Session) object;
                    String email = session.getCustomerEmail();
                    if (email == null && session.getCustomerDetails() != null) {
                        email = session.getCustomerDetails().getEmail();
                    }
                    StripeSubscriptionData sub = session.getSubscription() != null
                            ? retrieveSubscription(session.getSubscription()) : null;
                    yield new StripeWebhookEvent(event.getId(), type,
                            session.getClientReferenceId(), email, sub);
                }
                case "customer.subscription.updated", "customer.subscription.deleted" -> {
                    Subscription s = (Subscription) object;
                    yield new StripeWebhookEvent(event.getId(), type, null, null, toSubscriptionData(s));
                }
                case "invoice.payment_failed" -> {
                    Invoice invoice = (Invoice) object;
                    String subscriptionId = invoice.getParent() != null
                            && invoice.getParent().getSubscriptionDetails() != null
                            ? invoice.getParent().getSubscriptionDetails().getSubscription()
                            : null;
                    StripeSubscriptionData sub = subscriptionId != null
                            ? retrieveSubscription(subscriptionId) : null;
                    yield new StripeWebhookEvent(event.getId(), type, null, null, sub);
                }
                default -> new StripeWebhookEvent(event.getId(), type, null, null, null);
            };
        } catch (com.stripe.exception.SignatureVerificationException e) {
            throw new BusinessException("Invalid Stripe webhook signature");
        }
    }

    private StripeSubscriptionData toSubscriptionData(Subscription s) {
        String priceId = null;
        Long currentPeriodEndEpoch = null;
        if (s.getItems() != null && s.getItems().getData() != null
                && !s.getItems().getData().isEmpty()) {
            SubscriptionItem firstItem = s.getItems().getData().get(0);
            if (firstItem.getPrice() != null) {
                priceId = firstItem.getPrice().getId();
            }
            currentPeriodEndEpoch = firstItem.getCurrentPeriodEnd();
        }
        OffsetDateTime periodEnd = currentPeriodEndEpoch != null
                ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(currentPeriodEndEpoch), ZoneOffset.UTC)
                : null;
        boolean cancelAtEnd = Boolean.TRUE.equals(s.getCancelAtPeriodEnd());
        return new StripeSubscriptionData(
                s.getId(), s.getCustomer(), priceId, s.getStatus(), periodEnd, cancelAtEnd);
    }
}
