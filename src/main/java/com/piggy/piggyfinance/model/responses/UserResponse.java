package com.piggy.piggyfinance.model.responses;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        boolean whatsappLinked,
        LocalDateTime createdAt
) {}
