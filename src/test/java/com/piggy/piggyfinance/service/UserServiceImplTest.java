package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.UserResponse;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void getCurrentUser_withNoPhoneNumber_returnsWhatsAppLinkedFalse() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId).name("Gabriel").email("g@test.com")
                .password("hash").createdAt(LocalDateTime.now())
                .phoneNumber(null).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserResponse response = userService.getCurrentUser(userId);

        assertThat(response.whatsappLinked()).isFalse();
    }

    @Test
    void getCurrentUser_withPhoneNumber_returnsWhatsAppLinkedTrue() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId).name("Gabriel").email("g@test.com")
                .password("hash").createdAt(LocalDateTime.now())
                .phoneNumber("+5575981231503").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserResponse response = userService.getCurrentUser(userId);

        assertThat(response.whatsappLinked()).isTrue();
    }

    @Test
    void getCurrentUser_withUnknownId_throwsUserNotFoundException() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser(userId))
                .isInstanceOf(UserNotFoundException.class);
    }
}