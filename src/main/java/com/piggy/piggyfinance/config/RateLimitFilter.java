package com.piggy.piggyfinance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final List<String> RATE_LIMITED_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/forgot-password",
            "/api/v1/users/whatsapp/link/confirm",
            "/api/v1/billing/activate",
            "/api/v1/reports/pdf"
    );
    private static final List<String> WHATSAPP_AI_PATHS = List.of(
            "/api/v1/transactions/whatsapp/summary",
            "/api/v1/transactions/whatsapp/last",
            "/api/v1/goals/whatsapp",
            "/api/v1/billing/whatsapp/status"
    );
    private static final int MAX_REQUESTS = 5;
    private static final int WHATSAPP_AI_MAX_REQUESTS = 120;
    private static final long WINDOW_MS = 60_000;

    private record RequestWindow(AtomicInteger count, long startTime, int observedCount) {}

    private final ConcurrentHashMap<String, RequestWindow> requestCounts = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !RATE_LIMITED_PATHS.contains(uri) && !WHATSAPP_AI_PATHS.contains(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = resolveClientIp(request);
        String uri = request.getRequestURI();
        String key = clientIp + ":" + uri;
        int maxRequests = WHATSAPP_AI_PATHS.contains(uri) ? WHATSAPP_AI_MAX_REQUESTS : MAX_REQUESTS;

        RequestWindow window = requestCounts.compute(key, (k, existing) -> {
            long now = Instant.now().toEpochMilli();
            if (existing == null || now - existing.startTime() > WINDOW_MS) {
                return new RequestWindow(new AtomicInteger(1), now, 1);
            }
            int newCount = existing.count().incrementAndGet();
            return new RequestWindow(existing.count(), existing.startTime(), newCount);
        });

        if (window.observedCount() > maxRequests) {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
