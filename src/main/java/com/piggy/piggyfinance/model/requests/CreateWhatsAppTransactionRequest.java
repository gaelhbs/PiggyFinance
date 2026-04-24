package com.piggy.piggyfinance.model.requests;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateWhatsAppTransactionRequest(

        @NotBlank(message = "Phone number is required")
        String phoneNumber,

        @NotBlank(message = "Description is required")
        String description,

        @NotNull(message = "Amount is required")
        BigDecimal amount,

        @NotNull(message = "Type is required")
        TransactionType type,

        CategoryType category
) {}
