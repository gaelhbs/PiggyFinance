package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;

public record ConfirmWhatsAppLinkRequest(

        @NotBlank(message = "Phone number is required")
        String phoneNumber,

        @NotBlank(message = "Code is required")
        String code
) {}