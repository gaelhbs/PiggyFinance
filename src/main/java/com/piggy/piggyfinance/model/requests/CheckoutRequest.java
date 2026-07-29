package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank String priceAlias) {}
