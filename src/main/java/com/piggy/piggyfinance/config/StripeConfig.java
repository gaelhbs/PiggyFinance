package com.piggy.piggyfinance.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class StripeConfig {

    private final StripeProperties properties;

    @PostConstruct
    void init() {
        Stripe.apiKey = properties.getSecretKey();
    }
}
