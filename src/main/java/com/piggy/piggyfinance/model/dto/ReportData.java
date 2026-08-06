package com.piggy.piggyfinance.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReportData(
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        List<CategoryBreakdownItem> categoryBreakdown,
        List<TransactionLineItem> transactions,
        boolean transactionsTruncated,
        long totalTransactionCount,
        List<GoalProgressItem> goals
) {}
