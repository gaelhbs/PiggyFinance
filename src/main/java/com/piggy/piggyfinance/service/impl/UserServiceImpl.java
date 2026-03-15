package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.responses.UserResponse;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public UserResponse getCurrentUser(UUID userId) {
        log.debug("Fetching user: {}", userId);

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
