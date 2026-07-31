package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.EmailAlreadyExistsException;
import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.LoginRequest;
import com.piggy.piggyfinance.model.requests.RegisterRequest;
import com.piggy.piggyfinance.model.responses.LoginResponse;
import com.piggy.piggyfinance.model.responses.RegisterResponse;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.AuthService;
import com.piggy.piggyfinance.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        log.info("Registering new user");

        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .createdAt(LocalDateTime.now())
                .provisional(false)
                .build();

        User saved = userRepository.save(user);

        subscriptionRepository.save(Subscription.builder()
                .user(saved)
                .tier(SubscriptionTier.PRO)
                .status(SubscriptionStatus.TRIALING)
                .source(SubscriptionSource.INTERNAL)
                .trialEndsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .cancelAtPeriodEnd(false)
                .build());

        log.info("User registered successfully: {}", saved.getId());
        return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getCreatedAt());
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        log.info("Login attempt received");

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String token = jwtService.generateToken(user);

        log.info("Login successful for user: {}", user.getId());
        return new LoginResponse(token);
    }
}
