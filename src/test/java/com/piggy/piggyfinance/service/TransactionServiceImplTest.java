package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.CreateTransactionRequest;
import com.piggy.piggyfinance.mappers.TransactionMapper;
import com.piggy.piggyfinance.model.responses.TransactionResponse;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.TransactionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock TransactionRepository transactionRepository;
    @Mock UserRepository userRepository;
    @Mock TransactionMapper transactionMapper;
    @Mock com.piggy.piggyfinance.service.EntitlementService entitlementService;
    @InjectMocks TransactionServiceImpl service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId).name("Test").email("test@test.com")
                .password("hash").createdAt(LocalDateTime.now()).build();
    }

    @Test
    void createTransaction_expenseWithIncomeCategory_throwsBusinessException() {
        CreateTransactionRequest req = new CreateTransactionRequest(
                "Test", new BigDecimal("100"), TransactionType.EXPENSE, CategoryType.SALARY
        );

        assertThatThrownBy(() -> service.createTransaction(req, TransactionSourceEnum.APP, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void createTransaction_incomeWithExpenseCategory_throwsBusinessException() {
        CreateTransactionRequest req = new CreateTransactionRequest(
                "Test", new BigDecimal("100"), TransactionType.INCOME, CategoryType.FOOD
        );

        assertThatThrownBy(() -> service.createTransaction(req, TransactionSourceEnum.APP, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void createTransaction_expenseWithExpenseCategory_succeeds() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        Transaction tx = mock(Transaction.class);
        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.save(any())).thenReturn(tx);
        when(transactionMapper.toResponse(tx)).thenReturn(resp);

        CreateTransactionRequest req = new CreateTransactionRequest(
                "Test", new BigDecimal("100"), TransactionType.EXPENSE, CategoryType.FOOD
        );

        service.createTransaction(req, TransactionSourceEnum.APP, userId);
        verify(transactionRepository).save(any());
    }

    @Test
    void createTransaction_incomeWithIncomeCategory_succeeds() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        Transaction tx = mock(Transaction.class);
        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.save(any())).thenReturn(tx);
        when(transactionMapper.toResponse(tx)).thenReturn(resp);

        CreateTransactionRequest req = new CreateTransactionRequest(
                "Test", new BigDecimal("100"), TransactionType.INCOME, CategoryType.SALARY
        );

        service.createTransaction(req, TransactionSourceEnum.APP, userId);
        verify(transactionRepository).save(any());
    }

    @Test
    void createTransaction_zeroAmount_throwsBusinessException() {
        CreateTransactionRequest req = new CreateTransactionRequest(
                "Test", BigDecimal.ZERO, TransactionType.EXPENSE, CategoryType.FOOD
        );

        assertThatThrownBy(() -> service.createTransaction(req, TransactionSourceEnum.APP, userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createWhatsAppTransaction_nonPro_throwsFeatureLocked() {
        var phone = "+5575900000000";
        com.piggy.piggyfinance.model.User u = com.piggy.piggyfinance.model.User.builder()
                .id(java.util.UUID.randomUUID()).name("W").email("w@test.com")
                .password("h").createdAt(java.time.LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(java.util.Optional.of(u));
        org.mockito.Mockito.doThrow(new com.piggy.piggyfinance.exceptions.FeatureLockedException(
                        "This feature requires the PRO plan", SubscriptionTier.PRO))
                .when(entitlementService).requireTier(u.getId(), SubscriptionTier.PRO);

        var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
                phone, "Café", new BigDecimal("10"), TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.createWhatsAppTransaction(req))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_freeUserUnderMonthlyLimit_succeeds() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(entitlementService.getEffectiveTier(userId)).thenReturn(SubscriptionTier.FREE);
        when(transactionRepository.countByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(5L);
        Transaction tx = mock(Transaction.class);
        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.save(any())).thenReturn(tx);
        when(transactionMapper.toResponse(tx)).thenReturn(resp);

        var req = new CreateTransactionRequest("Test", new BigDecimal("100"),
                TransactionType.EXPENSE, CategoryType.FOOD);

        service.createTransaction(req, TransactionSourceEnum.APP, userId);
        verify(transactionRepository).save(any());
    }

    @Test
    void createTransaction_freeUserAtMonthlyLimit_throwsFeatureLocked() {
        when(entitlementService.getEffectiveTier(userId)).thenReturn(SubscriptionTier.FREE);
        when(transactionRepository.countByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(15L);

        var req = new CreateTransactionRequest("Test", new BigDecimal("100"),
                TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.createTransaction(req, TransactionSourceEnum.APP, userId))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_paidUser_skipsLimitCheck() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(entitlementService.getEffectiveTier(userId)).thenReturn(SubscriptionTier.PRO);
        Transaction tx = mock(Transaction.class);
        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.save(any())).thenReturn(tx);
        when(transactionMapper.toResponse(tx)).thenReturn(resp);

        var req = new CreateTransactionRequest("Test", new BigDecimal("100"),
                TransactionType.EXPENSE, CategoryType.FOOD);

        service.createTransaction(req, TransactionSourceEnum.APP, userId);
        verify(transactionRepository).save(any());
        verify(transactionRepository, never()).countByUserIdAndTimestampBetween(any(), any(), any());
    }
}