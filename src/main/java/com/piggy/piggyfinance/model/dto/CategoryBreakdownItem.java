package com.piggy.piggyfinance.model.dto;

import com.piggy.piggyfinance.enums.CategoryType;

import java.math.BigDecimal;

public record CategoryBreakdownItem(CategoryType category, BigDecimal total, BigDecimal percentage) {}
