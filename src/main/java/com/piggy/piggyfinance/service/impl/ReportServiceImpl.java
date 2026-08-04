package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.dto.CategoryBreakdownItem;
import com.piggy.piggyfinance.model.dto.CategoryTotal;
import com.piggy.piggyfinance.model.dto.GoalProgressItem;
import com.piggy.piggyfinance.model.dto.ReportData;
import com.piggy.piggyfinance.model.dto.TransactionLineItem;
import com.piggy.piggyfinance.model.filters.TransactionFilter;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.repository.specifications.TransactionSpecification;
import com.piggy.piggyfinance.service.EntitlementService;
import com.piggy.piggyfinance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private static final int MAX_TRANSACTIONS = 500;
    private static final String TEMPLATE_NAME = "reports/financial-report";

    private final TransactionRepository transactionRepository;
    private final GoalRepository goalRepository;
    private final EntitlementService entitlementService;
    private final ReportPdfGenerator pdfGenerator;

    @Override
    public byte[] generatePdf(UUID userId, LocalDate startDate, LocalDate endDate) {
        entitlementService.requireTier(userId, SubscriptionTier.ESSENCIAL);

        LocalDate from = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1);
        LocalDate to = endDate != null ? endDate : LocalDate.now();

        if (from.isAfter(to)) {
            throw new BusinessException("startDate must not be after endDate");
        }

        ReportData reportData = buildReportData(userId, from, to);
        return pdfGenerator.render(TEMPLATE_NAME, reportData);
    }

    private ReportData buildReportData(UUID userId, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX);

        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (var item : transactionRepository.getSummary(userId, start, end)) {
            if (item.type() == TransactionType.INCOME) {
                totalIncome = item.total();
            } else if (item.type() == TransactionType.EXPENSE) {
                totalExpense = item.total();
            }
        }
        BigDecimal balance = totalIncome.subtract(totalExpense);

        List<CategoryTotal> categoryTotals = transactionRepository.getExpenseByCategory(userId, start, end);
        BigDecimal finalTotalExpense = totalExpense;
        List<CategoryBreakdownItem> categoryBreakdown = categoryTotals.stream()
                .map(c -> new CategoryBreakdownItem(c.category(), c.total(), percentageOf(c.total(), finalTotalExpense)))
                .toList();

        TransactionFilter filter = new TransactionFilter();
        filter.setStartDate(from);
        filter.setEndDate(to);
        List<Transaction> allTransactions = transactionRepository.findAll(
                TransactionSpecification.byFilter(filter, userId),
                Sort.by(Sort.Direction.ASC, "timestamp"));

        long totalTransactionCount = allTransactions.size();
        boolean truncated = totalTransactionCount > MAX_TRANSACTIONS;
        List<TransactionLineItem> transactionLines = allTransactions.stream()
                .limit(MAX_TRANSACTIONS)
                .map(t -> new TransactionLineItem(
                        t.getTimestamp().toLocalDate(), t.getDescription(), t.getCategory(), t.getType(), t.getAmount()))
                .toList();

        List<Goal> goals = goalRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<GoalProgressItem> goalItems = goals.stream()
                .map(g -> new GoalProgressItem(
                        g.getName(), g.getCurrentAmount(), g.getTargetAmount(),
                        percentageOf(g.getCurrentAmount(), g.getTargetAmount())))
                .toList();

        return new ReportData(from, to, totalIncome, totalExpense, balance,
                categoryBreakdown, transactionLines, truncated, totalTransactionCount, goalItems);
    }

    private static BigDecimal percentageOf(BigDecimal part, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }
}
