package com.piggy.piggyfinance.model.responses;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        boolean whatsappLinked
) {}
