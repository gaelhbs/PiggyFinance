package com.piggy.piggyfinance.model.dto;

import com.piggy.piggyfinance.enums.CategoryType;

import java.math.BigDecimal;

public record CategoryTotal(CategoryType category, BigDecimal total) {}
