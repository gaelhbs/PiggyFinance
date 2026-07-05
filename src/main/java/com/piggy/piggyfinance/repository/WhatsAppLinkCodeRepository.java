package com.piggy.piggyfinance.repository;

import com.piggy.piggyfinance.model.WhatsAppLinkCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppLinkCodeRepository extends JpaRepository<WhatsAppLinkCode, UUID> {

    Optional<WhatsAppLinkCode> findByCode(String code);

    Optional<WhatsAppLinkCode> findFirstByUserIdAndUsedFalseAndExpiresAtAfter(UUID userId, OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM WhatsAppLinkCode c WHERE c.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
