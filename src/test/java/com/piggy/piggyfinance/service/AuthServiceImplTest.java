package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.RegisterRequest;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock com.piggy.piggyfinance.service.JwtService jwtService;
    @InjectMocks AuthServiceImpl service;

    @Captor ArgumentCaptor<Subscription> subCaptor;

    @Test
    void register_grantsSevenDayProTrial() {
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("hash");
        User saved = User.builder()
                .id(UUID.randomUUID()).name("New").email("new@test.com")
                .password("hash").createdAt(LocalDateTime.now()).build();
        when(userRepository.save(any(User.class))).thenReturn(saved);

        service.register(new RegisterRequest("New", "new@test.com", "password1"));

        verify(subscriptionRepository).save(subCaptor.capture());
        Subscription created = subCaptor.getValue();
        assertThat(created.getTier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(created.getSource()).isEqualTo(SubscriptionSource.INTERNAL);
        assertThat(created.getUser()).isEqualTo(saved);
        assertThat(created.getTrialEndsAt())
                .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(6))
                .isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusDays(8));
    }
}
