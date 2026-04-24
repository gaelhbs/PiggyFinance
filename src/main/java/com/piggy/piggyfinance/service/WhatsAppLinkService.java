package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.model.responses.WhatsAppLinkCodeResponse;

import java.util.UUID;

public interface WhatsAppLinkService {

    WhatsAppLinkCodeResponse generateCode(UUID userId);

    void confirmLink(String phoneNumber, String code);
}
