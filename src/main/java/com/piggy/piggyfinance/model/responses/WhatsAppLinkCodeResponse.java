package com.piggy.piggyfinance.model.responses;

import java.time.OffsetDateTime;

public record WhatsAppLinkCodeResponse(
        String code,
        OffsetDateTime expiresAt
) {}