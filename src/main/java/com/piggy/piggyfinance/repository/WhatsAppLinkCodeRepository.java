package com.piggy.piggyfinance.repository;

import com.piggy.piggyfinance.model.WhatsAppLinkCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppLinkCodeRepository extends JpaRepository<WhatsAppLinkCode, UUID> {

    Optional<WhatsAppLinkCode> findByCode(String code);

    Optional<WhatsAppLinkCode> findFirstByUserIdAndUsedFalseAndExpiresAtAfter(UUID userId, OffsetDateTime now);
}
