package com.piggy.piggyfinance.repository;

import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.dto.CategoryTotal;
import com.piggy.piggyfinance.model.dto.TransactionSummaryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Page<Transaction> findByUserEmail(String email, Pageable pageable);

    @Query("""
    select new com.piggy.piggyfinance.model.dto.TransactionSummaryItem(
        t.type,
        sum(t.amount)
    )
    from Transaction t
    where t.user.id = :userId
      and t.timestamp between :start and :end
    group by t.type
""")
    List<TransactionSummaryItem> getSummary(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Query("""
    select new com.piggy.piggyfinance.model.dto.CategoryTotal(
        t.category,
        sum(t.amount)
    )
    from Transaction t
    where t.user.id = :userId
      and t.type = com.piggy.piggyfinance.enums.TransactionType.EXPENSE
      and t.timestamp between :start and :end
    group by t.category
""")
    List<CategoryTotal> getExpenseByCategory(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Query("""
    SELECT COUNT(t) FROM Transaction t
    WHERE t.user.id = :userId
      AND t.timestamp BETWEEN :start AND :end
      AND t.source = com.piggy.piggyfinance.enums.TransactionSourceEnum.APP
""")
    long countAppTransactionsByUserIdAndTimestampBetween(@Param("userId") UUID userId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    @Modifying
    @Query("DELETE FROM Transaction t WHERE t.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
