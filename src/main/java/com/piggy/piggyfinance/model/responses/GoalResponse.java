package com.piggy.piggyfinance.model.responses;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record GoalResponse(
    UUID id,
    String name,
    BigDecimal targetAmount,
    BigDecimal currentAmount,
    String iconName,
    LocalDateTime createdAt
) {}
