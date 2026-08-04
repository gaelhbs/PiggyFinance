package com.piggy.piggyfinance.model.dto;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionLineItem(
        LocalDate date,
        String description,
        CategoryType category,
        TransactionType type,
        BigDecimal amount
) {}
