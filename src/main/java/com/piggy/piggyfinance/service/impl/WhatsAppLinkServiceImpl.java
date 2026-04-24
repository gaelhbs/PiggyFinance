package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.exceptions.*;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.WhatsAppLinkCode;
import com.piggy.piggyfinance.model.responses.WhatsAppLinkCodeResponse;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.repository.WhatsAppLinkCodeRepository;
import com.piggy.piggyfinance.service.WhatsAppLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppLinkServiceImpl implements WhatsAppLinkService {

    private static final int CODE_EXPIRY_MINUTES = 15;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WhatsAppLinkCodeRepository linkCodeRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public WhatsAppLinkCodeResponse generateCode(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        if (user.getPhoneNumber() != null) {
            throw new AccountAlreadyLinkedException("This account already has a WhatsApp number linked.");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return linkCodeRepository
                .findFirstByUserIdAndUsedFalseAndExpiresAtAfter(userId, now)
                .map(existing -> new WhatsAppLinkCodeResponse(existing.getCode(), existing.getExpiresAt()))
                .orElseGet(() -> {
                    WhatsAppLinkCode newCode = WhatsAppLinkCode.builder()
                            .user(user)
                            .code(generateUniqueCode())
                            .expiresAt(now.plusMinutes(CODE_EXPIRY_MINUTES))
                            .used(false)
                            .build();
                    linkCodeRepository.save(newCode);
                    log.info("Generated WhatsApp link code for user {}", userId);
                    return new WhatsAppLinkCodeResponse(newCode.getCode(), newCode.getExpiresAt());
                });
    }

    @Override
    @Transactional
    public void confirmLink(String phoneNumber, String code) {
        WhatsAppLinkCode linkCode = linkCodeRepository.findByCode(code)
                .orElseThrow(() -> new WhatsAppCodeNotFoundException("Code not found: " + code));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (linkCode.getExpiresAt().isBefore(now)) {
            throw new WhatsAppCodeExpiredException("Code has expired. Please generate a new one in the app.");
        }

        if (linkCode.isUsed()) {
            throw new WhatsAppCodeAlreadyUsedException("Code has already been used.");
        }

        if (userRepository.existsByPhoneNumber(phoneNumber)) {
            throw new PhoneAlreadyLinkedException("This phone number is already linked to another account.");
        }

        User owner = linkCode.getUser();

        if (owner.getPhoneNumber() != null) {
            throw new AccountAlreadyLinkedException("This account already has a WhatsApp number linked.");
        }

        userRepository.save(owner.toBuilder().phoneNumber(phoneNumber).build());
        linkCodeRepository.save(linkCode.toBuilder().used(true).build());

        log.info("WhatsApp phone {} linked to user {}", phoneNumber, owner.getId());
    }

    private String generateUniqueCode() {
        String candidate;
        do {
            candidate = String.format("PIGGY-%06d", RANDOM.nextInt(1_000_000));
        } while (linkCodeRepository.findByCode(candidate).isPresent());
        return candidate;
    }
}
