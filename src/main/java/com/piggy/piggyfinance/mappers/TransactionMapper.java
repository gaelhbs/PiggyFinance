package com.piggy.piggyfinance.mappers;

import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.responses.TransactionResponse;
import org.mapstruct.Mapper;
import org.springframework.data.domain.Page;

@Mapper(componentModel = "spring")
public interface TransactionMapper {

    TransactionResponse toResponse(Transaction transaction);

    default Page<TransactionResponse> toResponsePage(Page<Transaction> page) {
        return page.map(this::toResponse);
    }
}
