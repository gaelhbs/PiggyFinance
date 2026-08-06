package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.dto.CategoryTotal;
import com.piggy.piggyfinance.model.dto.ReportData;
import com.piggy.piggyfinance.model.dto.TransactionSummaryItem;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.service.impl.ReportPdfGenerator;
import com.piggy.piggyfinance.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock TransactionRepository transactionRepository;
    @Mock GoalRepository goalRepository;
    @Mock EntitlementService entitlementService;
    @Mock ReportPdfGenerator pdfGenerator;
    @InjectMocks ReportServiceImpl service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @Test
    void generatePdf_freeTier_throwsFeatureLockedAndTouchesNothingElse() {
        doThrow(new FeatureLockedException("This feature requires the ESSENCIAL plan", SubscriptionTier.ESSENCIAL))
                .when(entitlementService).requireTier(userId, SubscriptionTier.ESSENCIAL);

        assertThatThrownBy(() -> service.generatePdf(userId, null, null))
                .isInstanceOf(FeatureLockedException.class);

        verifyNoInteractions(transactionRepository, goalRepository, pdfGenerator);
    }

    @Test
    void generatePdf_startAfterEnd_throwsBusinessException() {
        LocalDate start = LocalDate.of(2026, 8, 10);
        LocalDate end = LocalDate.of(2026, 8, 1);

        assertThatThrownBy(() -> service.generatePdf(userId, start, end))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(transactionRepository, goalRepository, pdfGenerator);
    }

    @Test
    void generatePdf_aggregatesSummaryCategoryTransactionsAndGoals() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);

        when(transactionRepository.getSummary(eq(userId), any(), any())).thenReturn(List.of(
                new TransactionSummaryItem(TransactionType.INCOME, new BigDecimal("1000.00")),
                new TransactionSummaryItem(TransactionType.EXPENSE, new BigDecimal("400.00"))
        ));
        when(transactionRepository.getExpenseByCategory(eq(userId), any(), any())).thenReturn(List.of(
                new CategoryTotal(CategoryType.FOOD, new BigDecimal("300.00")),
                new CategoryTotal(CategoryType.TRANSPORT, new BigDecimal("100.00"))
        ));

        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID()).description("Mercado").amount(new BigDecimal("300.00"))
                .type(TransactionType.EXPENSE).category(CategoryType.FOOD)
                .timestamp(LocalDateTime.of(2026, 8, 5, 10, 0)).build();
        when(transactionRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(tx));

        Goal goal = Goal.builder()
                .id(UUID.randomUUID()).name("Viagem")
                .targetAmount(new BigDecimal("2000.00")).currentAmount(new BigDecimal("500.00")).build();
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(goal));

        when(pdfGenerator.render(anyString(), any(ReportData.class))).thenReturn(new byte[]{1, 2, 3});

        byte[] result = service.generatePdf(userId, start, end);

        assertThat(result).isEqualTo(new byte[]{1, 2, 3});

        ArgumentCaptor<ReportData> captor = ArgumentCaptor.forClass(ReportData.class);
        verify(pdfGenerator).render(eq("reports/financial-report"), captor.capture());
        ReportData data = captor.getValue();

        assertThat(data.startDate()).isEqualTo(start);
        assertThat(data.endDate()).isEqualTo(end);
        assertThat(data.totalIncome()).isEqualByComparingTo("1000.00");
        assertThat(data.totalExpense()).isEqualByComparingTo("400.00");
        assertThat(data.balance()).isEqualByComparingTo("600.00");
        assertThat(data.categoryBreakdown()).hasSize(2);
        assertThat(data.categoryBreakdown().get(0).category()).isEqualTo(CategoryType.FOOD);
        assertThat(data.categoryBreakdown().get(0).percentage()).isEqualByComparingTo("75.00");
        assertThat(data.transactions()).hasSize(1);
        assertThat(data.transactionsTruncated()).isFalse();
        assertThat(data.totalTransactionCount()).isEqualTo(1);
        assertThat(data.goals()).hasSize(1);
        assertThat(data.goals().get(0).percentage()).isEqualByComparingTo("25.00");
    }

    @Test
    void generatePdf_emptyPeriod_buildsEmptyReportDataWithDefaultDatesAndNoError() {
        when(transactionRepository.getSummary(eq(userId), any(), any())).thenReturn(List.of());
        when(transactionRepository.getExpenseByCategory(eq(userId), any(), any())).thenReturn(List.of());
        when(transactionRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());
        when(pdfGenerator.render(anyString(), any(ReportData.class))).thenReturn(new byte[]{9});

        byte[] result = service.generatePdf(userId, null, null);

        assertThat(result).isEqualTo(new byte[]{9});

        ArgumentCaptor<ReportData> captor = ArgumentCaptor.forClass(ReportData.class);
        verify(pdfGenerator).render(anyString(), captor.capture());
        ReportData data = captor.getValue();

        assertThat(data.startDate()).isEqualTo(LocalDate.now().withDayOfMonth(1));
        assertThat(data.endDate()).isEqualTo(LocalDate.now());
        assertThat(data.totalIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(data.totalExpense()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(data.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(data.categoryBreakdown()).isEmpty();
        assertThat(data.transactions()).isEmpty();
        assertThat(data.goals()).isEmpty();
    }

    @Test
    void generatePdf_moreThan500Transactions_truncatesListAndFlagsTruncation() {
        when(transactionRepository.getSummary(eq(userId), any(), any())).thenReturn(List.of());
        when(transactionRepository.getExpenseByCategory(eq(userId), any(), any())).thenReturn(List.of());

        List<Transaction> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(Transaction.builder()
                    .id(UUID.randomUUID()).description("Tx " + i).amount(BigDecimal.ONE)
                    .type(TransactionType.EXPENSE).category(CategoryType.FOOD)
                    .timestamp(LocalDateTime.of(2026, 8, 1, 0, 0).plusMinutes(i)).build());
        }
        when(transactionRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(many);
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());
        when(pdfGenerator.render(anyString(), any(ReportData.class))).thenReturn(new byte[]{1});

        service.generatePdf(userId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        ArgumentCaptor<ReportData> captor = ArgumentCaptor.forClass(ReportData.class);
        verify(pdfGenerator).render(anyString(), captor.capture());
        ReportData data = captor.getValue();

        assertThat(data.transactionsTruncated()).isTrue();
        assertThat(data.transactions()).hasSize(500);
        assertThat(data.totalTransactionCount()).isEqualTo(501);
    }
}
