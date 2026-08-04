package com.piggy.piggyfinance.model.dto;

import java.math.BigDecimal;

public record GoalProgressItem(String name, BigDecimal currentAmount, BigDecimal targetAmount, BigDecimal percentage) {}
