package com.piggy.piggyfinance.utils;

import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SecurityUtils {

    public static UUID getAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User is not authenticated");
        }

        if (!(authentication.getPrincipal() instanceof UUID userId)) {
            throw new UnauthorizedException("Invalid authentication principal");
        }

        return userId;
    }
}
