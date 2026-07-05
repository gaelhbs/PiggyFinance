package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.model.responses.UserResponse;

import java.util.UUID;

public interface UserService {
    UserResponse getCurrentUser(UUID userId);
    void deleteAccount(UUID userId, String currentPassword);
}
