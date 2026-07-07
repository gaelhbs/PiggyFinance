package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.model.requests.ConfirmWhatsAppLinkRequest;
import com.piggy.piggyfinance.model.requests.DeleteAccountRequest;
import com.piggy.piggyfinance.model.responses.UserResponse;
import com.piggy.piggyfinance.model.responses.WhatsAppLinkCodeResponse;
import com.piggy.piggyfinance.service.UserService;
import com.piggy.piggyfinance.service.WhatsAppLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final WhatsAppLinkService whatsAppLinkService;

    @GetMapping("/me")
    public UserResponse getCurrentUser(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return userService.getCurrentUser(userId);
    }

    @PostMapping("/whatsapp/link/generate")
    @ResponseStatus(HttpStatus.OK)
    public WhatsAppLinkCodeResponse generateWhatsAppLinkCode(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return whatsAppLinkService.generateCode(userId);
    }

    @PostMapping("/whatsapp/link/confirm")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> confirmWhatsAppLink(@RequestBody @Valid ConfirmWhatsAppLinkRequest request) {
        whatsAppLinkService.confirmLink(request.phoneNumber(), request.code());
        return Map.of("message", "Account linked successfully.");
    }

    @DeleteMapping("/whatsapp/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkWhatsApp(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        whatsAppLinkService.unlinkWhatsApp(userId);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@RequestBody @Valid DeleteAccountRequest request,
                              Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        userService.deleteAccount(userId, request.currentPassword());
    }
}
