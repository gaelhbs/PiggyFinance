package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.model.responses.ActivateResponse;
import com.piggy.piggyfinance.model.responses.WhatsAppSubscriptionStatusResponse;

import java.util.UUID;

public interface BillingService {
    String createCheckout(UUID userId, String priceAlias);
    String createPortal(UUID userId);
    void handleWebhook(String payload, String signatureHeader);
    ActivateResponse activate(String sessionId);
    WhatsAppSubscriptionStatusResponse getStatusByPhone(String phoneNumber);
}
