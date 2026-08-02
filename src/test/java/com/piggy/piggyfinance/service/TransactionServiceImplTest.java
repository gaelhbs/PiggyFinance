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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Captor ArgumentCaptor<Transaction> txCaptor;

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
    void createWhatsAppTransaction_phoneNotLinked_throwsPhoneNotLinkedException() {
        var phone = "+5575900000001";
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());

        var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
                phone, "Café", new BigDecimal("10"), TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.createWhatsAppTransaction(req))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.PhoneNotLinkedException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_freeUserUnderMonthlyLimit_succeeds() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(entitlementService.getEffectiveTier(userId)).thenReturn(SubscriptionTier.FREE);
        when(transactionRepository.countAppTransactionsByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(5L);
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
        when(transactionRepository.countAppTransactionsByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(15L);

        var req = new CreateTransactionRequest("Test", new BigDecimal("100"),
                TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.createTransaction(req, TransactionSourceEnum.APP, userId))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class)
                .satisfies(ex -> assertThat(((com.piggy.piggyfinance.exceptions.FeatureLockedException) ex).getRequiredTier())
                        .isEqualTo(SubscriptionTier.ESSENCIAL));
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
        verify(transactionRepository, never()).countAppTransactionsByUserIdAndTimestampBetween(any(), any(), any());
    }

    @Test
    void getSummaryByPhone_success_delegatesToGetSummary() {
        var phone = "+5575900000002";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w2@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        when(transactionRepository.getSummary(eq(u.getId()), any(), any())).thenReturn(java.util.List.of());

        var result = service.getSummaryByPhone(phone, null, null);

        assertThat(result.balance()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void getSummaryByPhone_phoneNotLinked_throws() {
        var phone = "+5575900000003";
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSummaryByPhone(phone, null, null))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.PhoneNotLinkedException.class);
    }

    @Test
    void getSummaryByPhone_nonPro_throwsFeatureLocked() {
        var phone = "+5575900000004";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w3@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        org.mockito.Mockito.doThrow(new com.piggy.piggyfinance.exceptions.FeatureLockedException(
                        "This feature requires the PRO plan", SubscriptionTier.PRO))
                .when(entitlementService).requireTier(u.getId(), SubscriptionTier.PRO);

        assertThatThrownBy(() -> service.getSummaryByPhone(phone, null, null))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
    }

    @Test
    void getLastWhatsAppTransaction_success_returnsMostRecent() {
        var phone = "+5575900000005";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w4@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        Transaction tx = mock(Transaction.class);
        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
                .thenReturn(Optional.of(tx));
        when(transactionMapper.toResponse(tx)).thenReturn(resp);

        assertThat(service.getLastWhatsAppTransaction(phone)).isEqualTo(resp);
    }

    @Test
    void getLastWhatsAppTransaction_noneFound_throwsWhatsAppTransactionNotFound() {
        var phone = "+5575900000006";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w5@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLastWhatsAppTransaction(phone))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.WhatsAppTransactionNotFoundException.class);
    }

    @Test
    void updateLastWhatsAppTransaction_success_replacesFieldsAndSaves() {
        var phone = "+5575900000007";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w6@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));

        UUID existingId = UUID.randomUUID();
        LocalDateTime existingTimestamp = LocalDateTime.now();
        Transaction existing = Transaction.builder()
                .id(existingId).description("Old").amount(new BigDecimal("10"))
                .type(TransactionType.EXPENSE).source(TransactionSourceEnum.WHATSAPP)
                .category(CategoryType.FOOD).timestamp(existingTimestamp).user(u).build();
        when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
                .thenReturn(Optional.of(existing));

        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionMapper.toResponse(any())).thenReturn(resp);

        var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
                phone, "Almoço", new BigDecimal("50"), TransactionType.EXPENSE, CategoryType.FOOD);

        assertThat(service.updateLastWhatsAppTransaction(req)).isEqualTo(resp);

        verify(transactionRepository).save(txCaptor.capture());
        Transaction saved = txCaptor.getValue();

        // Verify new fields were updated
        assertThat(saved.getDescription()).isEqualTo("Almoço");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(saved.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(saved.getCategory()).isEqualTo(CategoryType.FOOD);

        // Verify old fields were preserved (not replaced)
        assertThat(saved.getId()).isEqualTo(existingId);
        assertThat(saved.getSource()).isEqualTo(TransactionSourceEnum.WHATSAPP);
        assertThat(saved.getUser()).isEqualTo(u);
        assertThat(saved.getTimestamp()).isEqualTo(existingTimestamp);
    }

    @Test
    void updateLastWhatsAppTransaction_noneFound_throwsWhatsAppTransactionNotFound() {
        var phone = "+5575900000008";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w7@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
                .thenReturn(Optional.empty());

        var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
                phone, "Almoço", new BigDecimal("50"), TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.updateLastWhatsAppTransaction(req))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.WhatsAppTransactionNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateLastWhatsAppTransaction_invalidAmount_throwsBusinessException() {
        var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
                "+5575900000009", "Almoço", BigDecimal.ZERO, TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.updateLastWhatsAppTransaction(req))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).findByPhoneNumber(any());
    }

    @Test
    void deleteLastWhatsAppTransaction_success_deletes() {
        var phone = "+5575900000010";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w8@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        Transaction existing = Transaction.builder()
                .id(UUID.randomUUID()).description("Old").amount(new BigDecimal("10"))
                .type(TransactionType.EXPENSE).source(TransactionSourceEnum.WHATSAPP)
                .category(CategoryType.FOOD).timestamp(LocalDateTime.now()).user(u).build();
        when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
                .thenReturn(Optional.of(existing));

        service.deleteLastWhatsAppTransaction(phone);

        verify(transactionRepository).delete(existing);
    }

    @Test
    void deleteLastWhatsAppTransaction_noneFound_throwsWhatsAppTransactionNotFound() {
        var phone = "+5575900000011";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("w9@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteLastWhatsAppTransaction(phone))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.WhatsAppTransactionNotFoundException.class);
        verify(transactionRepository, never()).delete(any(Transaction.class));
    }
}