package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.UserResponse;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.repository.WhatsAppLinkCodeRepository;
import com.piggy.piggyfinance.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock WhatsAppLinkCodeRepository whatsAppLinkCodeRepository;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks UserServiceImpl userService;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId).name("Gabriel").email("g@test.com")
                .password("hash").createdAt(LocalDateTime.now())
                .phoneNumber(null).build();
    }

    @Test
    void getCurrentUser_withNoPhoneNumber_returnsWhatsAppLinkedFalse() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserResponse response = userService.getCurrentUser(userId);

        assertThat(response.whatsappLinked()).isFalse();
    }

    @Test
    void getCurrentUser_withPhoneNumber_returnsWhatsAppLinkedTrue() {
        User withPhone = user.toBuilder().phoneNumber("+5575981231503").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(withPhone));

        UserResponse response = userService.getCurrentUser(userId);

        assertThat(response.whatsappLinked()).isTrue();
    }

    @Test
    void getCurrentUser_withUnknownId_throwsUserNotFoundException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser(userId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deleteAccount_withCorrectPassword_deletesAllUserDataThenUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);

        userService.deleteAccount(userId, "password123");

        verify(transactionRepository).deleteAllByUserId(userId);
        verify(whatsAppLinkCodeRepository).deleteAllByUserId(userId);
        verify(userRepository).delete(user);
    }

    @Test
    void deleteAccount_withWrongPassword_throwsUnauthorizedException() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteAccount(userId, "wrong"))
                .isInstanceOf(UnauthorizedException.class);

        verify(transactionRepository, never()).deleteAllByUserId(any());
        verify(userRepository, never()).delete(any());
    }
}
