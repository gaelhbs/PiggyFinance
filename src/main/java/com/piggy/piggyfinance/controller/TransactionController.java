package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import com.piggy.piggyfinance.model.filters.TransactionFilter;
import com.piggy.piggyfinance.model.requests.CreateTransactionRequest;
import com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest;
import com.piggy.piggyfinance.model.responses.TransactionResponse;
import com.piggy.piggyfinance.model.responses.TransactionSummaryResponse;
import com.piggy.piggyfinance.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/app")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@RequestBody @Valid CreateTransactionRequest request,
                                      @AuthenticationPrincipal UUID userId) {
        return transactionService.createTransaction(request, TransactionSourceEnum.APP, userId);
    }

    @PostMapping("/whatsapp")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createFromWhatsApp(@RequestBody @Valid CreateWhatsAppTransactionRequest request) {
        return transactionService.createWhatsAppTransaction(request);
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public Page<TransactionResponse> list(TransactionFilter filter,
                                          @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC)
                                          Pageable pageable,
                                          @AuthenticationPrincipal UUID userId) {
        return transactionService.listTransactions(filter, pageable, userId);
    }

    @GetMapping("/summary")
    @ResponseStatus(HttpStatus.OK)
    public TransactionSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal UUID userId) {
        return transactionService.getSummary(userId, startDate, endDate);
    }

    @GetMapping("/whatsapp/summary")
    @ResponseStatus(HttpStatus.OK)
    public TransactionSummaryResponse whatsappSummary(
            @RequestParam String phoneNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return transactionService.getSummaryByPhone(phoneNumber, startDate, endDate);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,
                       @AuthenticationPrincipal UUID userId) {
        transactionService.deleteTransaction(id, userId);
    }
}
