package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record GoalProgressRequest(
    @NotNull @Positive BigDecimal amount
) {}
