package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.PhoneNotLinkedException;
import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.factory.TransactionFactory;
import com.piggy.piggyfinance.mappers.TransactionMapper;
import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.filters.TransactionFilter;
import com.piggy.piggyfinance.model.requests.CreateTransactionRequest;
import com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest;
import com.piggy.piggyfinance.model.responses.TransactionResponse;
import com.piggy.piggyfinance.model.responses.TransactionSummaryResponse;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.repository.specifications.TransactionSpecification;
import com.piggy.piggyfinance.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final TransactionMapper transactionMapper;

    @Override
    @Transactional
    public TransactionResponse createTransaction(CreateTransactionRequest request, TransactionSourceEnum source, UUID userId) {
        validate(request.amount(), request.type(), request.category());

        log.info("Creating {} transaction for user {}", source, userId);

        User user = findUserById(userId);
        Transaction saved = transactionRepository.save(TransactionFactory.create(request, source, user));

        log.info("Transaction created: {}", saved.getId());
        return transactionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TransactionResponse createWhatsAppTransaction(CreateWhatsAppTransactionRequest request) {
        validate(request.amount(), request.type(), request.category());

        log.info("Creating WhatsApp transaction for phone: {}", request.phoneNumber());

        User user = userRepository.findByPhoneNumber(request.phoneNumber())
                .orElseThrow(() -> new PhoneNotLinkedException(
                        "No account linked to this phone number. Please link your WhatsApp in the app."));

        CreateTransactionRequest transactionRequest = new CreateTransactionRequest(
                request.description(), request.amount(), request.type(), request.category()
        );

        Transaction saved = transactionRepository.save(
                TransactionFactory.create(transactionRequest, TransactionSourceEnum.WHATSAPP, user)
        );

        log.info("WhatsApp transaction created: {}", saved.getId());
        return transactionMapper.toResponse(saved);
    }

    @Override
    public Page<TransactionResponse> listTransactions(TransactionFilter filter, Pageable pageable, UUID userId) {
        log.debug("Listing transactions for user {}", userId);
        return transactionMapper.toResponsePage(
                transactionRepository.findAll(TransactionSpecification.byFilter(filter, userId), pageable)
        );
    }

    @Override
    public TransactionSummaryResponse getSummary(UUID userId, LocalDate startDate, LocalDate endDate) {
        LocalDate from = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1);
        LocalDate to = endDate != null ? endDate : LocalDate.now();

        log.debug("Getting summary for user {} from {} to {}", userId, from, to);

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX);

        var result = transactionRepository.getSummary(userId, start, end);

        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;

        for (var item : result) {
            if (item.type() == TransactionType.INCOME) {
                totalIncome = item.total();
            } else if (item.type() == TransactionType.EXPENSE) {
                totalExpense = item.total();
            }
        }

        return new TransactionSummaryResponse(totalIncome, totalExpense, totalIncome.subtract(totalExpense));
    }

    @Override
    @Transactional
    public void deleteTransaction(UUID transactionId, UUID userId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException("Transação não encontrada"));
        if (!transaction.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("Acesso negado");
        }
        transactionRepository.delete(transaction);
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));
    }

    private void validate(BigDecimal amount, TransactionType type, Object category) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Transaction amount must be greater than zero");
        }

        if (type == TransactionType.EXPENSE && category == null) {
            throw new BusinessException("Category is required for EXPENSE transactions");
        }
    }
}
