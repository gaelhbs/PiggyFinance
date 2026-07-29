package com.piggy.piggyfinance.config;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    private String secretKey;
    private String webhookSecret;
    private Map<String, String> prices = new HashMap<>();

    public String priceIdForAlias(String alias) {
        return prices.get(alias);
    }

    /** Deriva o tier pelo alias associado ao Price ID (aliases começam com "pro" ou "essencial"). */
    public SubscriptionTier tierForPriceId(String priceId) {
        return prices.entrySet().stream()
                .filter(e -> priceId != null && priceId.equals(e.getValue()))
                .map(e -> e.getKey().startsWith("pro") ? SubscriptionTier.PRO : SubscriptionTier.ESSENCIAL)
                .findFirst()
                .orElse(null);
    }
}
