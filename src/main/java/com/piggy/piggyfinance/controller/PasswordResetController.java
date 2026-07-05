package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.model.requests.ForgotPasswordRequest;
import com.piggy.piggyfinance.model.requests.ResetPasswordRequest;
import com.piggy.piggyfinance.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
        passwordResetService.sendResetLink(request.email());
        return Map.of("message", "If this email is registered, you will receive a password reset link shortly.");
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return Map.of("message", "Password reset successfully.");
    }
}
