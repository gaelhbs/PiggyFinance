package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String token,

        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters")
        @Pattern(
                regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$",
                message = "Password must contain letters and numbers"
        )
        String newPassword
) {}
