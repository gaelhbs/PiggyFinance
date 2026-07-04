package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import com.piggy.piggyfinance.enums.TransactionType;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock TransactionRepository transactionRepository;
    @Mock UserRepository userRepository;
    @Mock TransactionMapper transactionMapper;
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
}