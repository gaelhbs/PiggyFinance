package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record UpdateGoalRequest(
    @NotBlank String name,
    @NotNull @Positive BigDecimal targetAmount,
    @NotBlank String iconName
) {}
