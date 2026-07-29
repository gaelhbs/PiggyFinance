package com.piggy.piggyfinance;

import com.piggy.piggyfinance.config.StripeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(StripeProperties.class)
public class PiggyFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PiggyFinanceApplication.class, args);
    }

}
