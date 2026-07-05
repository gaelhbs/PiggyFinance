package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.PasswordResetToken;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.PasswordResetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JavaMailSender mailSender;
    @InjectMocks PasswordResetServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID()).name("Test").email("test@test.com")
                .password("hash").createdAt(LocalDateTime.now()).build();
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://piggyfinance.cloud");
        ReflectionTestUtils.setField(service, "mailFrom", "noreply@piggyfinance.cloud");
    }

    @Test
    void sendResetLink_withKnownEmail_invalidatesOldTokensAndSendsEmail() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        service.sendResetLink(user.getEmail());

        verify(tokenRepository).markAllUnusedByUserIdAsUsed(user.getId());
        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendResetLink_withUnknownEmail_doesNothingAndDoesNotRevealEmail() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        service.sendResetLink("unknown@test.com");

        verify(tokenRepository, never()).save(any());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void resetPassword_withValidToken_updatesPasswordAndMarksTokenUsed() {
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user).token("valid-token")
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10))
                .used(false).build();
        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("newPass1")).thenReturn("newHash");

        service.resetPassword("valid-token", "newPass1");

        verify(userRepository).save(any(User.class));
        verify(tokenRepository).save(argThat(t -> t.isUsed()));
    }

    @Test
    void resetPassword_withExpiredToken_throwsBusinessException() {
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user).token("expired-token")
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .used(false).build();
        when(tokenRepository.findByToken("expired-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.resetPassword("expired-token", "newPass1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resetPassword_withAlreadyUsedToken_throwsBusinessException() {
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user).token("used-token")
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10))
                .used(true).build();
        when(tokenRepository.findByToken("used-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.resetPassword("used-token", "newPass1"))
                .isInstanceOf(BusinessException.class);
    }
}
