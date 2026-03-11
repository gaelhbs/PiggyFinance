package com.piggy.piggyfinance.factory;

import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.CreateTransactionRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransactionFactory {

    public static Transaction create(CreateTransactionRequest request,
                                     TransactionSourceEnum source,
                                     User user) {
        return Transaction.builder()
                .description(request.description())
                .amount(request.amount())
                .type(request.type())
                .category(request.category())
                .source(source)
                .user(user)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
