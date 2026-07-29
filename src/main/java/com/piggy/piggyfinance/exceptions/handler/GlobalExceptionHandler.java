package com.piggy.piggyfinance.exceptions.handler;

import com.piggy.piggyfinance.exceptions.*;

import com.piggy.piggyfinance.model.responses.ErrorResponse;
import com.piggy.piggyfinance.model.responses.FeatureLockedResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("USER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        log.warn("Email conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("EMAIL_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        log.warn("Business rule violation: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("BUSINESS_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("UNAUTHORIZED", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", details);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", details));
    }

    @ExceptionHandler(WhatsAppCodeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWhatsAppCodeNotFound(WhatsAppCodeNotFoundException ex) {
        log.warn("WhatsApp code not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("CODE_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(WhatsAppCodeExpiredException.class)
    public ResponseEntity<ErrorResponse> handleWhatsAppCodeExpired(WhatsAppCodeExpiredException ex) {
        log.warn("WhatsApp code expired: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(ErrorResponse.of("CODE_EXPIRED", ex.getMessage()));
    }

    @ExceptionHandler(WhatsAppCodeAlreadyUsedException.class)
    public ResponseEntity<ErrorResponse> handleWhatsAppCodeAlreadyUsed(WhatsAppCodeAlreadyUsedException ex) {
        log.warn("WhatsApp code already used: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("CODE_ALREADY_USED", ex.getMessage()));
    }

    @ExceptionHandler(PhoneAlreadyLinkedException.class)
    public ResponseEntity<ErrorResponse> handlePhoneAlreadyLinked(PhoneAlreadyLinkedException ex) {
        log.warn("Phone already linked: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("PHONE_ALREADY_LINKED", ex.getMessage()));
    }

    @ExceptionHandler(AccountAlreadyLinkedException.class)
    public ResponseEntity<ErrorResponse> handleAccountAlreadyLinked(AccountAlreadyLinkedException ex) {
        log.warn("Account already linked: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ALREADY_LINKED", ex.getMessage()));
    }

    @ExceptionHandler(PhoneNotLinkedException.class)
    public ResponseEntity<ErrorResponse> handlePhoneNotLinked(PhoneNotLinkedException ex) {
        log.warn("Phone not linked: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("PHONE_NOT_LINKED", ex.getMessage()));
    }

    @ExceptionHandler(FeatureLockedException.class)
    public ResponseEntity<FeatureLockedResponse> handleFeatureLocked(FeatureLockedException ex) {
        log.warn("Feature locked: {} (requires {})", ex.getMessage(), ex.getRequiredTier());
        return ResponseEntity
                .status(HttpStatus.PAYMENT_REQUIRED)
                .body(FeatureLockedResponse.of(ex.getMessage(), ex.getRequiredTier()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
