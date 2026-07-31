package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.model.requests.ActivateRequest;
import com.piggy.piggyfinance.model.requests.CheckoutRequest;
import com.piggy.piggyfinance.model.responses.ActivateResponse;
import com.piggy.piggyfinance.model.responses.CheckoutResponse;
import com.piggy.piggyfinance.model.responses.PortalResponse;
import com.piggy.piggyfinance.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.OK)
    public CheckoutResponse checkout(@RequestBody @Valid CheckoutRequest request,
                                     @AuthenticationPrincipal UUID userId) {
        return new CheckoutResponse(billingService.createCheckout(userId, request.priceAlias()));
    }

    @PostMapping("/portal")
    @ResponseStatus(HttpStatus.OK)
    public PortalResponse portal(@AuthenticationPrincipal UUID userId) {
        return new PortalResponse(billingService.createPortal(userId));
    }

    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void webhook(@RequestBody String payload,
                        @RequestHeader("Stripe-Signature") String signature) {
        billingService.handleWebhook(payload, signature);
    }

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.OK)
    public ActivateResponse activate(@RequestBody @Valid ActivateRequest request) {
        return billingService.activate(request.sessionId());
    }
}
