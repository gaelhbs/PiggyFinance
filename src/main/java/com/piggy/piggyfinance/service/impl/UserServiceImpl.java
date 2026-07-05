package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.UserResponse;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.repository.WhatsAppLinkCodeRepository;
import com.piggy.piggyfinance.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final WhatsAppLinkCodeRepository whatsAppLinkCodeRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserResponse getCurrentUser(UUID userId) {
        log.debug("Fetching user: {}", userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));
        return new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getPhoneNumber() != null, user.getCreatedAt());
    }

    @Override
    @Transactional
    public void deleteAccount(UUID userId, String currentPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new UnauthorizedException("Invalid password");
        }

        transactionRepository.deleteAllByUserId(userId);
        whatsAppLinkCodeRepository.deleteAllByUserId(userId);
        userRepository.delete(user);

        log.info("Account deleted for user {}", userId);
    }
}
