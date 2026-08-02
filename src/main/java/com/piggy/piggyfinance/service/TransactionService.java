package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import com.piggy.piggyfinance.model.filters.TransactionFilter;
import com.piggy.piggyfinance.model.requests.CreateTransactionRequest;
import com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest;
import com.piggy.piggyfinance.model.responses.TransactionResponse;
import com.piggy.piggyfinance.model.responses.TransactionSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

public interface TransactionService {

    TransactionResponse createTransaction(CreateTransactionRequest request, TransactionSourceEnum source, UUID userId);

    TransactionResponse createWhatsAppTransaction(CreateWhatsAppTransactionRequest request);

    Page<TransactionResponse> listTransactions(TransactionFilter filter, Pageable pageable, UUID userId);

    TransactionSummaryResponse getSummary(UUID userId, LocalDate startDate, LocalDate endDate);

    TransactionSummaryResponse getSummaryByPhone(String phoneNumber, LocalDate startDate, LocalDate endDate);

    TransactionResponse getLastWhatsAppTransaction(String phoneNumber);

    void deleteTransaction(UUID transactionId, UUID userId);
}
