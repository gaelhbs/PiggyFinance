package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.PasswordResetToken;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final int TOKEN_EXPIRY_MINUTES = 30;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${app.mail.from}")
    private String mailFrom;

    @Override
    @Transactional
    public void sendResetLink(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            tokenRepository.markAllUnusedByUserIdAsUsed(user.getId());

            String token = UUID.randomUUID().toString();
            tokenRepository.save(PasswordResetToken.builder()
                    .user(user)
                    .token(token)
                    .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(TOKEN_EXPIRY_MINUTES))
                    .used(false)
                    .build());

            sendEmail(user.getEmail(), token);
            log.info("Password reset link generated for user {}", user.getId());
        });
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException("Invalid or expired password reset token"));

        if (resetToken.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BusinessException("Invalid or expired password reset token");
        }
        if (resetToken.isUsed()) {
            throw new BusinessException("Invalid or expired password reset token");
        }

        userRepository.save(resetToken.getUser().toBuilder()
                .password(passwordEncoder.encode(newPassword))
                .build());
        tokenRepository.save(resetToken.toBuilder().used(true).build());

        log.info("Password reset for user {}", resetToken.getUser().getId());
    }

    private void sendEmail(String to, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(to);
        message.setSubject("Redefinição de senha — PiggyFinance");
        message.setText(
                "Você solicitou a redefinição de sua senha no PiggyFinance.\n\n"
                + "Clique no link abaixo para criar uma nova senha (válido por 30 minutos):\n\n"
                + appBaseUrl + "/reset-password?token=" + token + "\n\n"
                + "Se você não solicitou isso, ignore este email."
        );
        mailSender.send(message);
    }
}
