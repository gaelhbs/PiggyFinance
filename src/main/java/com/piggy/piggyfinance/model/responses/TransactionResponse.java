package com.piggy.piggyfinance.model.responses;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import com.piggy.piggyfinance.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String description,
        BigDecimal amount,
        TransactionType type,
        TransactionSourceEnum source,
        CategoryType category,
        LocalDateTime timestamp
) {}
