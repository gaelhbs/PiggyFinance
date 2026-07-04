package com.piggy.piggyfinance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final List<String> API_KEY_PATHS = List.of(
            "/api/v1/transactions/whatsapp",
            "/api/v1/users/whatsapp/link/confirm"
    );
    private static final String API_KEY_HEADER = "X-Api-Key";

    @Value("${api.key}")
    private String apiKey;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !API_KEY_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);

        if (providedKey != null && MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                providedKey.getBytes(StandardCharsets.UTF_8))) {
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("whatsapp-integration", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("WhatsApp API key authentication successful");
        } else {
            log.warn("Invalid or missing API key for WhatsApp endpoint");
        }

        filterChain.doFilter(request, response);
    }
}
