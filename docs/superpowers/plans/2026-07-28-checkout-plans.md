# Checkout & Planos — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar monetização por assinatura (Free / Essencial / Pro, trial de 7 dias, Stripe) ao backend do PiggyFinance, com o acesso governado por uma camada de *entitlement* própria.

**Architecture:** Backend-first, entitlement-first. Uma tabela `subscriptions` é a fonte da verdade de acesso; `EntitlementService` resolve o tier vigente e é o único ponto de gating. O Stripe é a única fonte de pagamento no lançamento e alimenta o entitlement via checkout + webhook + activate. App e n8n nunca falam com o Stripe. As tasks são backend-only e cada uma termina com um deliverable testável.

**Tech Stack:** Java 21, Spring Boot 4.0.2, Spring Security, Spring Data JPA, Flyway, PostgreSQL, Lombok, JUnit 5 + Mockito + AssertJ, `com.stripe:stripe-java` (SDK oficial).

## Global Constraints

- Repo: `/Users/gabrielbraga/Documents/Projects/java/PiggyFinance`
- Tiers e ordem (para comparação): `FREE < ESSENCIAL < PRO` (usar `enum.ordinal()`)
- Trial: **7 dias**, tier `PRO`, `status = TRIALING`, `source = INTERNAL`, concedido **uma única vez** no `register`
- Limite Free: **15 transações/mês** criadas no app (`source = APP`); **1 meta no total** (qualquer linha em `goals` conta)
- IA WhatsApp exige **PRO**; Relatórios exigem **ESSENCIAL+** (o gate fica disponível; os endpoints de relatório são outro subsistema)
- Recorrência por **cartão**; intervalos **mensal + anual** (4 Price IDs); sem Pix recorrente
- Campos temporais das novas entidades: `OffsetDateTime` ↔ `TIMESTAMPTZ` (padrão de `PasswordResetToken`/V8)
- Erro de gating: HTTP **402 Payment Required**, corpo estruturado com campo `requiredTier` legível por máquina
- `BusinessException` → 422; `UnauthorizedException` → 401 (já existentes no `GlobalExceptionHandler`)
- Nova rota pública (sem JWT): `POST /api/v1/billing/webhook` e `POST /api/v1/billing/activate`
- Commits em inglês; prefixos: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`
- Rodar da raiz do repo. Testes: `./gradlew test --tests "<FQCN>"`; build: `./gradlew build`

---

## File Map

### Novos arquivos

| Arquivo | Responsabilidade |
|---|---|
| `src/main/resources/db/migration/V9__subscriptions.sql` | Tabela `subscriptions` |
| `enums/SubscriptionTier.java` | `FREE, ESSENCIAL, PRO` |
| `enums/SubscriptionStatus.java` | `TRIALING, ACTIVE, PAST_DUE, CANCELED, EXPIRED` |
| `enums/SubscriptionSource.java` | `INTERNAL, STRIPE, APPLE, GOOGLE` |
| `model/Subscription.java` | Entidade JPA da assinatura |
| `repository/SubscriptionRepository.java` | Queries de assinatura |
| `exceptions/FeatureLockedException.java` | Exceção de gating (carrega `requiredTier`) |
| `model/responses/FeatureLockedResponse.java` | Corpo 402 com `requiredTier` |
| `service/EntitlementService.java` + `service/impl/EntitlementServiceImpl.java` | Resolução de tier vigente + gating |
| `config/StripeProperties.java` | `@ConfigurationProperties("stripe")` |
| `config/StripeConfig.java` | Inicializa `Stripe.apiKey` |
| `service/stripe/StripeGateway.java` + `service/stripe/impl/StripeGatewayImpl.java` | Adapter isolando o SDK do Stripe |
| `service/stripe/dto/StripeCheckoutData.java` | DTO de sessão de checkout |
| `service/stripe/dto/StripeSubscriptionData.java` | DTO de assinatura Stripe |
| `service/stripe/dto/StripeWebhookEvent.java` | DTO normalizado de evento |
| `service/BillingService.java` + `service/impl/BillingServiceImpl.java` | Checkout, portal, webhook, activate |
| `controller/BillingController.java` | Endpoints `/api/v1/billing/*` |
| `model/requests/CheckoutRequest.java` | `{ priceAlias }` |
| `model/requests/ActivateRequest.java` | `{ sessionId }` |
| `model/responses/CheckoutResponse.java` | `{ url }` |
| `model/responses/PortalResponse.java` | `{ url }` |
| `model/responses/ActivateResponse.java` | `{ setupToken, email }` |
| `job/SubscriptionExpiryJob.java` | Sweep diário de trials/assinaturas vencidos |
| Testes correspondentes em `src/test/.../service/` | Ver cada task |

### Arquivos modificados

| Arquivo | Mudança |
|---|---|
| `build.gradle` | `com.stripe:stripe-java` |
| `src/main/resources/application.yml` | Seção `stripe` |
| `PiggyFinanceApplication.java` | `@EnableScheduling` + `@EnableConfigurationProperties(StripeProperties.class)` |
| `service/impl/AuthServiceImpl.java` | Cria subscription de trial no `register` |
| `service/impl/TransactionServiceImpl.java` | Gate Pro no WhatsApp + limite Free/mês no app |
| `service/impl/GoalServiceImpl.java` | Cap de 1 meta no Free |
| `repository/TransactionRepository.java` | `countByUserIdAndTimestampBetween` |
| `repository/GoalRepository.java` | `countByUserId` |
| `config/SecurityConfig.java` | Rotas públicas de billing |
| `config/RateLimitFilter.java` | Inclui `/api/v1/billing/activate` |
| `exceptions/handler/GlobalExceptionHandler.java` | Handler `FeatureLockedException` → 402 |

---

## Task 1: Enums, migration V9, entidade e repositório de `subscriptions`

**Files:**
- Create: `src/main/resources/db/migration/V9__subscriptions.sql`
- Create: `src/main/java/com/piggy/piggyfinance/enums/SubscriptionTier.java`
- Create: `src/main/java/com/piggy/piggyfinance/enums/SubscriptionStatus.java`
- Create: `src/main/java/com/piggy/piggyfinance/enums/SubscriptionSource.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/Subscription.java`
- Create: `src/main/java/com/piggy/piggyfinance/repository/SubscriptionRepository.java`

**Interfaces:**
- Produces:
  - `enum SubscriptionTier { FREE, ESSENCIAL, PRO }` (ordem = nível de acesso)
  - `enum SubscriptionStatus { TRIALING, ACTIVE, PAST_DUE, CANCELED, EXPIRED }`
  - `enum SubscriptionSource { INTERNAL, STRIPE, APPLE, GOOGLE }`
  - `Subscription` (Lombok `@Builder(toBuilder = true)`, getters) com: `id`, `user`, `tier`, `status`, `source`, `stripeCustomerId`, `stripeSubscriptionId`, `trialEndsAt`, `currentPeriodEnd`, `cancelAtPeriodEnd`, `createdAt`, `updatedAt`
  - `SubscriptionRepository.findByUserId(UUID) → Optional<Subscription>`
  - `SubscriptionRepository.findByStripeCustomerId(String) → Optional<Subscription>`
  - `SubscriptionRepository.findByStripeSubscriptionId(String) → Optional<Subscription>`
  - `SubscriptionRepository.findExpired(OffsetDateTime now) → List<Subscription>`

- [ ] **Step 1: Criar a migration**

Criar `src/main/resources/db/migration/V9__subscriptions.sql`:

```sql
CREATE TABLE subscriptions (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    tier                   VARCHAR(20)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    source                 VARCHAR(20)  NOT NULL,
    stripe_customer_id     VARCHAR(255),
    stripe_subscription_id VARCHAR(255) UNIQUE,
    trial_ends_at          TIMESTAMPTZ,
    current_period_end     TIMESTAMPTZ,
    cancel_at_period_end   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_user_id         ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_stripe_customer ON subscriptions(stripe_customer_id);
```

- [ ] **Step 2: Criar os três enums**

`src/main/java/com/piggy/piggyfinance/enums/SubscriptionTier.java`:

```java
package com.piggy.piggyfinance.enums;

public enum SubscriptionTier {
    FREE,
    ESSENCIAL,
    PRO
}
```

`src/main/java/com/piggy/piggyfinance/enums/SubscriptionStatus.java`:

```java
package com.piggy.piggyfinance.enums;

public enum SubscriptionStatus {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    CANCELED,
    EXPIRED
}
```

`src/main/java/com/piggy/piggyfinance/enums/SubscriptionSource.java`:

```java
package com.piggy.piggyfinance.enums;

public enum SubscriptionSource {
    INTERNAL,
    STRIPE,
    APPLE,
    GOOGLE
}
```

- [ ] **Step 3: Criar a entidade `Subscription`**

`src/main/java/com/piggy/piggyfinance/model/Subscription.java`:

```java
package com.piggy.piggyfinance.model;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionSource source;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", unique = true)
    private String stripeSubscriptionId;

    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    @Column(name = "current_period_end")
    private OffsetDateTime currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 4: Criar o repositório**

`src/main/java/com/piggy/piggyfinance/repository/SubscriptionRepository.java`:

```java
package com.piggy.piggyfinance.repository;

import com.piggy.piggyfinance.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByUserId(UUID userId);

    Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    @Query("""
        SELECT s FROM Subscription s
        WHERE (s.status = com.piggy.piggyfinance.enums.SubscriptionStatus.TRIALING AND s.trialEndsAt < :now)
           OR (s.status IN (com.piggy.piggyfinance.enums.SubscriptionStatus.ACTIVE,
                            com.piggy.piggyfinance.enums.SubscriptionStatus.PAST_DUE)
               AND s.currentPeriodEnd < :now)
        """)
    List<Subscription> findExpired(@Param("now") OffsetDateTime now);
}
```

- [ ] **Step 5: Compilar**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V9__subscriptions.sql \
        src/main/java/com/piggy/piggyfinance/enums/SubscriptionTier.java \
        src/main/java/com/piggy/piggyfinance/enums/SubscriptionStatus.java \
        src/main/java/com/piggy/piggyfinance/enums/SubscriptionSource.java \
        src/main/java/com/piggy/piggyfinance/model/Subscription.java \
        src/main/java/com/piggy/piggyfinance/repository/SubscriptionRepository.java
git commit -m "feat: add subscriptions table, entity, enums and repository"
```

---

## Task 2: `EntitlementService` + exceção/erro de gating

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/exceptions/FeatureLockedException.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/responses/FeatureLockedResponse.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/EntitlementService.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/impl/EntitlementServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/exceptions/handler/GlobalExceptionHandler.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/EntitlementServiceImplTest.java`

**Interfaces:**
- Consumes: `SubscriptionRepository.findByUserId` (Task 1), `SubscriptionTier`, `SubscriptionStatus`
- Produces:
  - `FeatureLockedException(String message, SubscriptionTier requiredTier)` com getter `getRequiredTier()`
  - `EntitlementService.getEffectiveTier(UUID) → SubscriptionTier`
  - `EntitlementService.hasAtLeast(UUID, SubscriptionTier) → boolean`
  - `EntitlementService.requireTier(UUID, SubscriptionTier)` — lança `FeatureLockedException` se não atende
  - Handler que mapeia `FeatureLockedException` → HTTP 402 + `FeatureLockedResponse`

- [ ] **Step 1: Criar a exceção**

`src/main/java/com/piggy/piggyfinance/exceptions/FeatureLockedException.java`:

```java
package com.piggy.piggyfinance.exceptions;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import lombok.Getter;

@Getter
public class FeatureLockedException extends RuntimeException {
    private final SubscriptionTier requiredTier;

    public FeatureLockedException(String message, SubscriptionTier requiredTier) {
        super(message);
        this.requiredTier = requiredTier;
    }
}
```

- [ ] **Step 2: Criar o corpo de resposta 402**

`src/main/java/com/piggy/piggyfinance/model/responses/FeatureLockedResponse.java`:

```java
package com.piggy.piggyfinance.model.responses;

import com.piggy.piggyfinance.enums.SubscriptionTier;

import java.time.LocalDateTime;

public record FeatureLockedResponse(
        String code,
        String message,
        String requiredTier,
        LocalDateTime timestamp
) {
    public static FeatureLockedResponse of(String message, SubscriptionTier requiredTier) {
        return new FeatureLockedResponse("FEATURE_LOCKED", message, requiredTier.name(), LocalDateTime.now());
    }
}
```

- [ ] **Step 3: Criar a interface do serviço**

`src/main/java/com/piggy/piggyfinance/service/EntitlementService.java`:

```java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.SubscriptionTier;

import java.util.UUID;

public interface EntitlementService {
    SubscriptionTier getEffectiveTier(UUID userId);
    boolean hasAtLeast(UUID userId, SubscriptionTier minimum);
    void requireTier(UUID userId, SubscriptionTier minimum);
}
```

- [ ] **Step 4: Escrever o teste que deve falhar**

`src/test/java/com/piggy/piggyfinance/service/EntitlementServiceImplTest.java`:

```java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.service.impl.EntitlementServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceImplTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @InjectMocks EntitlementServiceImpl service;

    private final UUID userId = UUID.randomUUID();

    private Subscription sub(SubscriptionTier tier, SubscriptionStatus status,
                            OffsetDateTime trialEndsAt, OffsetDateTime periodEnd) {
        return Subscription.builder()
                .tier(tier).status(status).source(SubscriptionSource.INTERNAL)
                .trialEndsAt(trialEndsAt).currentPeriodEnd(periodEnd)
                .build();
    }

    private OffsetDateTime future() { return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1); }
    private OffsetDateTime past()   { return OffsetDateTime.now(ZoneOffset.UTC).minusDays(1); }

    @Test
    void noSubscription_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.empty());
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void trialingWithinWindow_resolvesTierPro() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.TRIALING, future(), null)));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void trialingExpired_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.TRIALING, past(), null)));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void activeWithFuturePeriod_resolvesTier() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.ESSENCIAL, SubscriptionStatus.ACTIVE, null, future())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.ESSENCIAL);
    }

    @Test
    void activeExpiredPeriod_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.ACTIVE, null, past())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void pastDueWithinPeriod_stillResolvesTier() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.PAST_DUE, null, future())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void canceled_resolvesFree() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.CANCELED, null, future())));
        assertThat(service.getEffectiveTier(userId)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void hasAtLeast_comparesByOrder() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.ESSENCIAL, SubscriptionStatus.ACTIVE, null, future())));
        assertThat(service.hasAtLeast(userId, SubscriptionTier.ESSENCIAL)).isTrue();
        assertThat(service.hasAtLeast(userId, SubscriptionTier.PRO)).isFalse();
        assertThat(service.hasAtLeast(userId, SubscriptionTier.FREE)).isTrue();
    }

    @Test
    void requireTier_throwsWhenBelow() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.FREE, SubscriptionStatus.ACTIVE, null, future())));
        assertThatThrownBy(() -> service.requireTier(userId, SubscriptionTier.PRO))
                .isInstanceOf(FeatureLockedException.class)
                .satisfies(ex -> assertThat(((FeatureLockedException) ex).getRequiredTier())
                        .isEqualTo(SubscriptionTier.PRO));
    }

    @Test
    void requireTier_passesWhenMet() {
        when(subscriptionRepository.findByUserId(userId))
                .thenReturn(Optional.of(sub(SubscriptionTier.PRO, SubscriptionStatus.ACTIVE, null, future())));
        assertThatCode(() -> service.requireTier(userId, SubscriptionTier.PRO)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 5: Rodar para confirmar falha (classe impl ainda não existe)**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.EntitlementServiceImplTest" 2>&1 | tail -15`
Expected: falha de compilação (`EntitlementServiceImpl` não existe).

- [ ] **Step 6: Implementar `EntitlementServiceImpl`**

`src/main/java/com/piggy/piggyfinance/service/impl/EntitlementServiceImpl.java`:

```java
package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementServiceImpl implements EntitlementService {

    private final SubscriptionRepository subscriptionRepository;

    @Override
    public SubscriptionTier getEffectiveTier(UUID userId) {
        return subscriptionRepository.findByUserId(userId)
                .map(this::resolve)
                .orElse(SubscriptionTier.FREE);
    }

    private SubscriptionTier resolve(Subscription s) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return switch (s.getStatus()) {
            case TRIALING -> (s.getTrialEndsAt() != null && s.getTrialEndsAt().isAfter(now))
                    ? s.getTier() : SubscriptionTier.FREE;
            case ACTIVE -> (s.getCurrentPeriodEnd() == null || s.getCurrentPeriodEnd().isAfter(now))
                    ? s.getTier() : SubscriptionTier.FREE;
            case PAST_DUE -> (s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isAfter(now))
                    ? s.getTier() : SubscriptionTier.FREE;
            default -> SubscriptionTier.FREE;
        };
    }

    @Override
    public boolean hasAtLeast(UUID userId, SubscriptionTier minimum) {
        return getEffectiveTier(userId).ordinal() >= minimum.ordinal();
    }

    @Override
    public void requireTier(UUID userId, SubscriptionTier minimum) {
        if (!hasAtLeast(userId, minimum)) {
            throw new FeatureLockedException(
                    "This feature requires the " + minimum + " plan", minimum);
        }
    }
}
```

- [ ] **Step 7: Rodar para confirmar aprovação**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.EntitlementServiceImplTest" 2>&1 | tail -15`
Expected: `11 tests completed, 0 failures`

- [ ] **Step 8: Adicionar o handler 402 no `GlobalExceptionHandler`**

Em `src/main/java/com/piggy/piggyfinance/exceptions/handler/GlobalExceptionHandler.java`, adicionar o import e o método handler (antes do `handleGeneric`):

Imports a adicionar:
```java
import com.piggy.piggyfinance.model.responses.FeatureLockedResponse;
```

Método a adicionar:
```java
@ExceptionHandler(FeatureLockedException.class)
public ResponseEntity<FeatureLockedResponse> handleFeatureLocked(FeatureLockedException ex) {
    log.warn("Feature locked: {} (requires {})", ex.getMessage(), ex.getRequiredTier());
    return ResponseEntity
            .status(HttpStatus.PAYMENT_REQUIRED)
            .body(FeatureLockedResponse.of(ex.getMessage(), ex.getRequiredTier()));
}
```

> Nota: `FeatureLockedException` está em `com.piggy.piggyfinance.exceptions.*`, já coberto pelo import wildcard existente no topo do handler.

- [ ] **Step 9: Compilar**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/exceptions/FeatureLockedException.java \
        src/main/java/com/piggy/piggyfinance/model/responses/FeatureLockedResponse.java \
        src/main/java/com/piggy/piggyfinance/service/EntitlementService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/EntitlementServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/exceptions/handler/GlobalExceptionHandler.java \
        src/test/java/com/piggy/piggyfinance/service/EntitlementServiceImplTest.java
git commit -m "feat: add EntitlementService with tier resolution and 402 feature-locked handling"
```

---

## Task 3: Conceder trial de 7 dias no `register`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/AuthServiceImpl.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/AuthServiceImplTest.java`

**Interfaces:**
- Consumes: `SubscriptionRepository.save` (Task 1), `SubscriptionTier.PRO`, `SubscriptionStatus.TRIALING`, `SubscriptionSource.INTERNAL`
- Produces: após `register`, existe uma `Subscription` `PRO / TRIALING / INTERNAL` com `trialEndsAt ≈ now + 7d` para o novo usuário

- [ ] **Step 1: Escrever o teste que deve falhar**

`src/test/java/com/piggy/piggyfinance/service/AuthServiceImplTest.java`:

```java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.RegisterRequest;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock com.piggy.piggyfinance.service.JwtService jwtService;
    @InjectMocks AuthServiceImpl service;

    @Captor ArgumentCaptor<Subscription> subCaptor;

    @Test
    void register_grantsSevenDayProTrial() {
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("hash");
        User saved = User.builder()
                .id(UUID.randomUUID()).name("New").email("new@test.com")
                .password("hash").createdAt(LocalDateTime.now()).build();
        when(userRepository.save(any(User.class))).thenReturn(saved);

        service.register(new RegisterRequest("New", "new@test.com", "password1"));

        verify(subscriptionRepository).save(subCaptor.capture());
        Subscription created = subCaptor.getValue();
        assertThat(created.getTier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(created.getSource()).isEqualTo(SubscriptionSource.INTERNAL);
        assertThat(created.getUser()).isEqualTo(saved);
        assertThat(created.getTrialEndsAt())
                .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(6))
                .isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusDays(8));
    }
}
```

> Nota: verificar os nomes exatos dos campos de `RegisterRequest` antes de rodar — o construtor usado aqui é `(name, email, password)`. Se a ordem diferir no record real, ajustar a chamada.

- [ ] **Step 2: Rodar para confirmar falha**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.AuthServiceImplTest" 2>&1 | tail -15`
Expected: falha — `AuthServiceImpl` ainda não injeta `SubscriptionRepository` nem chama `save`.

- [ ] **Step 3: Modificar `AuthServiceImpl`**

Em `src/main/java/com/piggy/piggyfinance/service/impl/AuthServiceImpl.java`:

Adicionar imports:
```java
import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
```

Adicionar o campo (junto dos outros `private final`):
```java
    private final SubscriptionRepository subscriptionRepository;
```

No método `register`, após `User saved = userRepository.save(user);` e antes do `log.info(...)` final, inserir:
```java
        subscriptionRepository.save(Subscription.builder()
                .user(saved)
                .tier(SubscriptionTier.PRO)
                .status(SubscriptionStatus.TRIALING)
                .source(SubscriptionSource.INTERNAL)
                .trialEndsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .cancelAtPeriodEnd(false)
                .build());
```

- [ ] **Step 4: Rodar para confirmar aprovação**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.AuthServiceImplTest" 2>&1 | tail -15`
Expected: `1 test completed, 0 failures`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/AuthServiceImpl.java \
        src/test/java/com/piggy/piggyfinance/service/AuthServiceImplTest.java
git commit -m "feat: grant 7-day Pro trial on user registration"
```

---

## Task 4: Gating — Pro no WhatsApp, limite Free/mês no app, cap de metas

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java`
- Modify: `src/main/java/com/piggy/piggyfinance/repository/GoalRepository.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java` (modificar)
- Test: `src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java` (modificar)

**Interfaces:**
- Consumes: `EntitlementService.getEffectiveTier`, `EntitlementService.requireTier` (Task 2)
- Produces:
  - `TransactionRepository.countByUserIdAndTimestampBetween(UUID, LocalDateTime, LocalDateTime) → long`
  - `GoalRepository.countByUserId(UUID) → long`
  - `createWhatsAppTransaction` exige PRO; `createTransaction(APP)` bloqueia o 16º/mês no Free; `GoalServiceImpl.create` bloqueia a 2ª meta no Free

- [ ] **Step 1: Adicionar as queries de contagem**

Em `src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java`, adicionar (antes do `deleteAllByUserId`):
```java
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.user.id = :userId AND t.timestamp BETWEEN :start AND :end")
    long countByUserIdAndTimestampBetween(@Param("userId") UUID userId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);
```
(Os imports `Query`, `Param` e `LocalDateTime`, `UUID` já existem neste arquivo.)

Em `src/main/java/com/piggy/piggyfinance/repository/GoalRepository.java`, adicionar dentro da interface:
```java
    long countByUserId(UUID userId);
```

- [ ] **Step 2: Escrever os testes que devem falhar (transações)**

Em `src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java`:

Adicionar o mock do serviço de entitlement e importar os tipos. Adicionar ao bloco de mocks existente:
```java
    @Mock com.piggy.piggyfinance.service.EntitlementService entitlementService;
```
(O `@InjectMocks TransactionServiceImpl service;` já existe e passará a receber o novo mock. `TransactionServiceImpl` **não** depende de `SubscriptionRepository` diretamente — o gating passa só pelo `EntitlementService`.)

Adicionar imports no topo:
```java
import com.piggy.piggyfinance.enums.SubscriptionTier;
```

Adicionar os novos testes na classe:
```java
    @Test
    void createWhatsAppTransaction_nonPro_throwsFeatureLocked() {
        var phone = "+5575900000000";
        com.piggy.piggyfinance.model.User u = com.piggy.piggyfinance.model.User.builder()
                .id(java.util.UUID.randomUUID()).name("W").email("w@test.com")
                .password("h").createdAt(java.time.LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(java.util.Optional.of(u));
        org.mockito.Mockito.doThrow(new com.piggy.piggyfinance.exceptions.FeatureLockedException(
                        "This feature requires the PRO plan", SubscriptionTier.PRO))
                .when(entitlementService).requireTier(u.getId(), SubscriptionTier.PRO);

        var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
                phone, "Café", new BigDecimal("10"), TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.createWhatsAppTransaction(req))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_freeUserUnderMonthlyLimit_succeeds() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(entitlementService.getEffectiveTier(userId)).thenReturn(SubscriptionTier.FREE);
        when(transactionRepository.countByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(5L);
        Transaction tx = mock(Transaction.class);
        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.save(any())).thenReturn(tx);
        when(transactionMapper.toResponse(tx)).thenReturn(resp);

        var req = new CreateTransactionRequest("Test", new BigDecimal("100"),
                TransactionType.EXPENSE, CategoryType.FOOD);

        service.createTransaction(req, TransactionSourceEnum.APP, userId);
        verify(transactionRepository).save(any());
    }

    @Test
    void createTransaction_freeUserAtMonthlyLimit_throwsFeatureLocked() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(entitlementService.getEffectiveTier(userId)).thenReturn(SubscriptionTier.FREE);
        when(transactionRepository.countByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(15L);

        var req = new CreateTransactionRequest("Test", new BigDecimal("100"),
                TransactionType.EXPENSE, CategoryType.FOOD);

        assertThatThrownBy(() -> service.createTransaction(req, TransactionSourceEnum.APP, userId))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_paidUser_skipsLimitCheck() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(entitlementService.getEffectiveTier(userId)).thenReturn(SubscriptionTier.PRO);
        Transaction tx = mock(Transaction.class);
        TransactionResponse resp = mock(TransactionResponse.class);
        when(transactionRepository.save(any())).thenReturn(tx);
        when(transactionMapper.toResponse(tx)).thenReturn(resp);

        var req = new CreateTransactionRequest("Test", new BigDecimal("100"),
                TransactionType.EXPENSE, CategoryType.FOOD);

        service.createTransaction(req, TransactionSourceEnum.APP, userId);
        verify(transactionRepository).save(any());
        verify(transactionRepository, never()).countByUserIdAndTimestampBetween(any(), any(), any());
    }
```

Adicionar os imports estáticos que faltarem no arquivo (se ainda não presentes):
```java
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
```

- [ ] **Step 3: Rodar para confirmar falha**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest" 2>&1 | tail -20`
Expected: falha de compilação (`entitlementService` não é dependência de `TransactionServiceImpl`).

- [ ] **Step 4: Implementar o gating em `TransactionServiceImpl`**

Em `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`:

Adicionar imports:
```java
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.service.EntitlementService;
import java.time.LocalDate;
```
(`LocalDate` e `LocalDateTime`/`LocalTime` já são importados no arquivo.)

Adicionar a dependência e a constante:
```java
    private final EntitlementService entitlementService;

    private static final int FREE_MONTHLY_TRANSACTION_LIMIT = 15;
```

No método `createTransaction`, logo após `validate(...)` e antes de `User user = findUserById(userId);`, inserir:
```java
        if (source == TransactionSourceEnum.APP
                && entitlementService.getEffectiveTier(userId) == SubscriptionTier.FREE) {
            LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
            LocalDateTime monthEnd = LocalDate.now().atTime(LocalTime.MAX);
            long used = transactionRepository.countByUserIdAndTimestampBetween(userId, monthStart, monthEnd);
            if (used >= FREE_MONTHLY_TRANSACTION_LIMIT) {
                throw new FeatureLockedException(
                        "O plano Free permite até " + FREE_MONTHLY_TRANSACTION_LIMIT
                                + " transações por mês. Faça upgrade para continuar.",
                        SubscriptionTier.ESSENCIAL);
            }
        }
```

No método `createWhatsAppTransaction`, logo após resolver o `User user` (o `orElseThrow` do `findByPhoneNumber`) e antes de montar o `CreateTransactionRequest`, inserir:
```java
        entitlementService.requireTier(user.getId(), SubscriptionTier.PRO);
```

- [ ] **Step 5: Escrever os testes que devem falhar (metas)**

Em `src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java`, adicionar o mock do entitlement:
```java
    @Mock com.piggy.piggyfinance.service.EntitlementService entitlementService;
```
(`@InjectMocks GoalServiceImpl` já existe.)

Adicionar os testes:
```java
    @Test
    void create_freeUserWithExistingGoal_throwsFeatureLocked() {
        when(entitlementService.getEffectiveTier(userId))
                .thenReturn(com.piggy.piggyfinance.enums.SubscriptionTier.FREE);
        when(goalRepository.countByUserId(userId)).thenReturn(1L);

        var req = new com.piggy.piggyfinance.model.requests.CreateGoalRequest(
                "Viagem", new java.math.BigDecimal("1000"), null, "plane");

        assertThatThrownBy(() -> service.create(req, userId))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
        verify(goalRepository, never()).save(any());
    }

    @Test
    void create_paidUser_skipsGoalCap() {
        when(entitlementService.getEffectiveTier(userId))
                .thenReturn(com.piggy.piggyfinance.enums.SubscriptionTier.PRO);
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        com.piggy.piggyfinance.model.Goal g = mock(com.piggy.piggyfinance.model.Goal.class);
        when(goalRepository.save(any())).thenReturn(g);

        var req = new com.piggy.piggyfinance.model.requests.CreateGoalRequest(
                "Viagem", new java.math.BigDecimal("1000"), null, "plane");

        service.create(req, userId);
        verify(goalRepository).save(any());
        verify(goalRepository, never()).countByUserId(any());
    }
```

> Nota: confirmar a assinatura real do record `CreateGoalRequest` e do `GoalResponse` no arquivo de teste existente antes de rodar — reutilizar exatamente o padrão de `mock(Goal.class)`/stubbing que o `GoalServiceImplTest` atual já usa em `create`, ajustando os campos se necessário. Ajustar `userId`/`user` para os nomes já definidos no `setUp` da classe.

- [ ] **Step 6: Implementar o cap em `GoalServiceImpl`**

Em `src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java`:

Adicionar imports:
```java
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.service.EntitlementService;
```

Adicionar a dependência e a constante:
```java
    private final EntitlementService entitlementService;

    private static final long FREE_GOAL_LIMIT = 1;
```

No início do método `create`, antes de `User user = findUser(userId);`, inserir:
```java
        if (entitlementService.getEffectiveTier(userId) == SubscriptionTier.FREE
                && goalRepository.countByUserId(userId) >= FREE_GOAL_LIMIT) {
            throw new FeatureLockedException(
                    "O plano Free permite apenas 1 meta. Faça upgrade para criar mais.",
                    SubscriptionTier.ESSENCIAL);
        }
```

- [ ] **Step 7: Rodar os dois testes**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest" --tests "com.piggy.piggyfinance.service.GoalServiceImplTest" 2>&1 | tail -20`
Expected: todos os testes passam (`0 failures`).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java \
        src/main/java/com/piggy/piggyfinance/repository/GoalRepository.java \
        src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java \
        src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java \
        src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java
git commit -m "feat: gate WhatsApp AI on Pro and enforce Free monthly-transaction and goal limits"
```

---

## Task 5: Dependência Stripe, config e scheduling

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/piggy/piggyfinance/config/StripeProperties.java`
- Create: `src/main/java/com/piggy/piggyfinance/config/StripeConfig.java`
- Modify: `src/main/java/com/piggy/piggyfinance/PiggyFinanceApplication.java`

**Interfaces:**
- Produces:
  - `StripeProperties` com `getSecretKey()`, `getWebhookSecret()`, `getPrices()` (`Map<String,String>` alias→priceId), `priceIdForAlias(String)`, `tierForPriceId(String) → SubscriptionTier`
  - `Stripe.apiKey` inicializado no boot
  - `@EnableScheduling` ativo (para Task 10)

- [ ] **Step 1: Adicionar a dependência do Stripe**

Em `build.gradle`, na seção `dependencies`, após a linha do `jjwt-api`, adicionar:
```groovy
    implementation 'com.stripe:stripe-java:29.1.0'
```

> Se a resolução falhar, verificar a versão estável mais recente em https://central.sonatype.com/artifact/com.stripe/stripe-java e usar essa. Confirmar com: `./gradlew dependencies --configuration runtimeClasspath 2>&1 | grep stripe`.

- [ ] **Step 2: Adicionar a seção `stripe` ao `application.yml`**

Ao final de `src/main/resources/application.yml`, adicionar:
```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET}
  prices:
    essencial-monthly: ${STRIPE_PRICE_ESSENCIAL_MONTHLY}
    essencial-annual:  ${STRIPE_PRICE_ESSENCIAL_ANNUAL}
    pro-monthly:       ${STRIPE_PRICE_PRO_MONTHLY}
    pro-annual:        ${STRIPE_PRICE_PRO_ANNUAL}
```

- [ ] **Step 3: Criar `StripeProperties`**

`src/main/java/com/piggy/piggyfinance/config/StripeProperties.java`:

```java
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
```

- [ ] **Step 4: Criar `StripeConfig` (inicializa a API key)**

`src/main/java/com/piggy/piggyfinance/config/StripeConfig.java`:

```java
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
```

- [ ] **Step 5: Ativar properties + scheduling na aplicação**

Substituir `src/main/java/com/piggy/piggyfinance/PiggyFinanceApplication.java`:

```java
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
```

- [ ] **Step 6: Compilar**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/resources/application.yml \
        src/main/java/com/piggy/piggyfinance/config/StripeProperties.java \
        src/main/java/com/piggy/piggyfinance/config/StripeConfig.java \
        src/main/java/com/piggy/piggyfinance/PiggyFinanceApplication.java
git commit -m "feat: add Stripe SDK, configuration properties and enable scheduling"
```

---

## Task 6: Adapter do Stripe (`StripeGateway`) + DTOs normalizados

Isola todo o SDK do Stripe atrás de uma interface, para que o `BillingService` (Tasks 7–9) seja testável com mock. Os DTOs são records simples sem tipos do Stripe.

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/service/stripe/dto/StripeCheckoutData.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/stripe/dto/StripeSubscriptionData.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/stripe/dto/StripeWebhookEvent.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/stripe/StripeGateway.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/stripe/impl/StripeGatewayImpl.java`

**Interfaces:**
- Consumes: `StripeProperties` (Task 5), SDK `com.stripe.*`
- Produces:
  - `StripeCheckoutData(String sessionId, String customerId, String subscriptionId, String clientReferenceId, String customerEmail, boolean paid)`
  - `StripeSubscriptionData(String subscriptionId, String customerId, String priceId, String status, OffsetDateTime currentPeriodEnd, boolean cancelAtPeriodEnd)`
  - `StripeWebhookEvent(String id, String type, String clientReferenceId, String customerEmail, StripeSubscriptionData subscription)`
  - `StripeGateway`:
    - `String createCustomer(String email)`
    - `String createCheckoutSession(String customerId, String userId, String priceId, String successUrl, String cancelUrl)` → URL
    - `String createPortalSession(String customerId, String returnUrl)` → URL
    - `StripeCheckoutData retrieveCheckoutSession(String sessionId)`
    - `StripeSubscriptionData retrieveSubscription(String subscriptionId)`
    - `StripeWebhookEvent parseWebhookEvent(String payload, String signatureHeader)`

- [ ] **Step 1: Criar os DTOs**

`src/main/java/com/piggy/piggyfinance/service/stripe/dto/StripeCheckoutData.java`:
```java
package com.piggy.piggyfinance.service.stripe.dto;

public record StripeCheckoutData(
        String sessionId,
        String customerId,
        String subscriptionId,
        String clientReferenceId,
        String customerEmail,
        boolean paid
) {}
```

`src/main/java/com/piggy/piggyfinance/service/stripe/dto/StripeSubscriptionData.java`:
```java
package com.piggy.piggyfinance.service.stripe.dto;

import java.time.OffsetDateTime;

public record StripeSubscriptionData(
        String subscriptionId,
        String customerId,
        String priceId,
        String status,
        OffsetDateTime currentPeriodEnd,
        boolean cancelAtPeriodEnd
) {}
```

`src/main/java/com/piggy/piggyfinance/service/stripe/dto/StripeWebhookEvent.java`:
```java
package com.piggy.piggyfinance.service.stripe.dto;

public record StripeWebhookEvent(
        String id,
        String type,
        String clientReferenceId,
        String customerEmail,
        StripeSubscriptionData subscription
) {}
```

- [ ] **Step 2: Criar a interface `StripeGateway`**

`src/main/java/com/piggy/piggyfinance/service/stripe/StripeGateway.java`:
```java
package com.piggy.piggyfinance.service.stripe;

import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;

public interface StripeGateway {
    String createCustomer(String email);
    String createCheckoutSession(String customerId, String userId, String priceId,
                                 String successUrl, String cancelUrl);
    String createPortalSession(String customerId, String returnUrl);
    StripeCheckoutData retrieveCheckoutSession(String sessionId);
    StripeSubscriptionData retrieveSubscription(String subscriptionId);
    StripeWebhookEvent parseWebhookEvent(String payload, String signatureHeader);
}
```

- [ ] **Step 3: Implementar `StripeGatewayImpl`**

`src/main/java/com/piggy/piggyfinance/service/stripe/impl/StripeGatewayImpl.java`:
```java
package com.piggy.piggyfinance.service.stripe.impl;

import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripeGatewayImpl implements StripeGateway {

    private final StripeProperties properties;

    @Override
    public String createCustomer(String email) {
        try {
            Customer customer = Customer.create(
                    CustomerCreateParams.builder().setEmail(email).build());
            return customer.getId();
        } catch (StripeException e) {
            throw new BusinessException("Failed to create Stripe customer: " + e.getMessage());
        }
    }

    @Override
    public String createCheckoutSession(String customerId, String userId, String priceId,
                                        String successUrl, String cancelUrl) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customerId)
                    .setClientReferenceId(userId)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(priceId).setQuantity(1L).build())
                    .build();
            return Session.create(params).getUrl();
        } catch (StripeException e) {
            throw new BusinessException("Failed to create checkout session: " + e.getMessage());
        }
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        try {
            com.stripe.param.billingportal.SessionCreateParams params =
                    com.stripe.param.billingportal.SessionCreateParams.builder()
                            .setCustomer(customerId)
                            .setReturnUrl(returnUrl)
                            .build();
            return com.stripe.model.billingportal.Session.create(params).getUrl();
        } catch (StripeException e) {
            throw new BusinessException("Failed to create billing portal session: " + e.getMessage());
        }
    }

    @Override
    public StripeCheckoutData retrieveCheckoutSession(String sessionId) {
        try {
            Session session = Session.retrieve(sessionId);
            boolean paid = "paid".equals(session.getPaymentStatus())
                    || "complete".equals(session.getStatus());
            String email = session.getCustomerEmail();
            if (email == null && session.getCustomerDetails() != null) {
                email = session.getCustomerDetails().getEmail();
            }
            return new StripeCheckoutData(
                    session.getId(),
                    session.getCustomer(),
                    session.getSubscription(),
                    session.getClientReferenceId(),
                    email,
                    paid);
        } catch (StripeException e) {
            throw new BusinessException("Failed to retrieve checkout session: " + e.getMessage());
        }
    }

    @Override
    public StripeSubscriptionData retrieveSubscription(String subscriptionId) {
        try {
            Subscription s = Subscription.retrieve(subscriptionId);
            return toSubscriptionData(s);
        } catch (StripeException e) {
            throw new BusinessException("Failed to retrieve subscription: " + e.getMessage());
        }
    }

    @Override
    public StripeWebhookEvent parseWebhookEvent(String payload, String signatureHeader) {
        try {
            Event event = Webhook.constructEvent(payload, signatureHeader, properties.getWebhookSecret());
            StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
            String type = event.getType();

            return switch (type) {
                case "checkout.session.completed" -> {
                    Session session = (Session) object;
                    String email = session.getCustomerEmail();
                    if (email == null && session.getCustomerDetails() != null) {
                        email = session.getCustomerDetails().getEmail();
                    }
                    StripeSubscriptionData sub = session.getSubscription() != null
                            ? retrieveSubscription(session.getSubscription()) : null;
                    yield new StripeWebhookEvent(event.getId(), type,
                            session.getClientReferenceId(), email, sub);
                }
                case "customer.subscription.updated", "customer.subscription.deleted" -> {
                    Subscription s = (Subscription) object;
                    yield new StripeWebhookEvent(event.getId(), type, null, null, toSubscriptionData(s));
                }
                case "invoice.payment_failed" -> {
                    Invoice invoice = (Invoice) object;
                    StripeSubscriptionData sub = invoice.getSubscription() != null
                            ? retrieveSubscription(invoice.getSubscription()) : null;
                    yield new StripeWebhookEvent(event.getId(), type, null, null, sub);
                }
                default -> new StripeWebhookEvent(event.getId(), type, null, null, null);
            };
        } catch (com.stripe.exception.SignatureVerificationException e) {
            throw new BusinessException("Invalid Stripe webhook signature");
        }
    }

    private StripeSubscriptionData toSubscriptionData(Subscription s) {
        String priceId = null;
        if (s.getItems() != null && s.getItems().getData() != null
                && !s.getItems().getData().isEmpty()
                && s.getItems().getData().get(0).getPrice() != null) {
            priceId = s.getItems().getData().get(0).getPrice().getId();
        }
        OffsetDateTime periodEnd = s.getCurrentPeriodEnd() != null
                ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(s.getCurrentPeriodEnd()), ZoneOffset.UTC)
                : null;
        boolean cancelAtEnd = Boolean.TRUE.equals(s.getCancelAtPeriodEnd());
        return new StripeSubscriptionData(
                s.getId(), s.getCustomer(), priceId, s.getStatus(), periodEnd, cancelAtEnd);
    }
}
```

> Nota (drift do SDK): esta é a única camada que toca o SDK do Stripe. Se algum método/tipo divergir na versão resolvida (ex.: `getCurrentPeriodEnd`, `getSubscription` em `Invoice`, `getCustomerDetails`), ajustar **apenas aqui** — a lógica testada vive no `BillingService`, que só depende dos DTOs acima. Após ajustar, rodar `./gradlew compileJava`.

- [ ] **Step 4: Compilar**

Run: `./gradlew compileJava 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL` (ajustar a camada do adapter se o SDK divergir, conforme a nota).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/stripe/
git commit -m "feat: add Stripe gateway adapter isolating the SDK behind normalized DTOs"
```

---

## Task 7: `BillingService` — checkout e portal + controller + rotas públicas

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/model/requests/CheckoutRequest.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/requests/ActivateRequest.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/responses/CheckoutResponse.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/responses/PortalResponse.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/responses/ActivateResponse.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/BillingService.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java`
- Create: `src/main/java/com/piggy/piggyfinance/controller/BillingController.java`
- Modify: `src/main/java/com/piggy/piggyfinance/config/SecurityConfig.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java`

**Interfaces:**
- Consumes: `StripeGateway` (Task 6), `SubscriptionRepository`, `UserRepository`, `StripeProperties`
- Produces:
  - `BillingService.createCheckout(UUID userId, String priceAlias) → String url`
  - `BillingService.createPortal(UUID userId) → String url`
  - (Task 8 adiciona `handleWebhook`; Task 9 adiciona `activate`)
  - `POST /api/v1/billing/checkout` (auth) → `CheckoutResponse`
  - `POST /api/v1/billing/portal` (auth) → `PortalResponse`

- [ ] **Step 1: Criar os DTOs de request/response**

`CheckoutRequest.java`:
```java
package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank String priceAlias) {}
```

`ActivateRequest.java`:
```java
package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;

public record ActivateRequest(@NotBlank String sessionId) {}
```

`CheckoutResponse.java`:
```java
package com.piggy.piggyfinance.model.responses;

public record CheckoutResponse(String url) {}
```

`PortalResponse.java`:
```java
package com.piggy.piggyfinance.model.responses;

public record PortalResponse(String url) {}
```

`ActivateResponse.java`:
```java
package com.piggy.piggyfinance.model.responses;

public record ActivateResponse(String setupToken, String email) {}
```

- [ ] **Step 2: Criar a interface `BillingService`**

`src/main/java/com/piggy/piggyfinance/service/BillingService.java`:
```java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.model.responses.ActivateResponse;

import java.util.UUID;

public interface BillingService {
    String createCheckout(UUID userId, String priceAlias);
    String createPortal(UUID userId);
    void handleWebhook(String payload, String signatureHeader);
    ActivateResponse activate(String sessionId);
}
```

- [ ] **Step 3: Escrever os testes de checkout/portal**

`src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java`:
```java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.BillingServiceImpl;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingServiceImplTest {

    @Mock StripeGateway stripeGateway;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock StripeProperties stripeProperties;
    @InjectMocks BillingServiceImpl service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder().id(userId).name("Gab").email("gab@test.com")
                .password("hash").createdAt(LocalDateTime.now()).build();
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://piggyfinance.cloud");
    }

    private Subscription trialSub() {
        return Subscription.builder()
                .user(user).tier(SubscriptionTier.PRO).status(SubscriptionStatus.TRIALING)
                .source(SubscriptionSource.INTERNAL)
                .trialEndsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(3))
                .build();
    }

    @Test
    void createCheckout_createsCustomerAndReturnsUrl() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        when(stripeProperties.priceIdForAlias("pro-monthly")).thenReturn("price_123");
        when(stripeGateway.createCustomer("gab@test.com")).thenReturn("cus_1");
        when(stripeGateway.createCheckoutSession(eq("cus_1"), eq(userId.toString()), eq("price_123"),
                any(), any())).thenReturn("https://checkout.stripe.com/x");

        String url = service.createCheckout(userId, "pro-monthly");

        assertThat(url).isEqualTo("https://checkout.stripe.com/x");
    }

    @Test
    void createCheckout_unknownAlias_throwsBusinessException() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        when(stripeProperties.priceIdForAlias("bogus")).thenReturn(null);

        assertThatThrownBy(() -> service.createCheckout(userId, "bogus"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createCheckout_alreadyActiveStripeSub_throwsBusinessException() {
        Subscription active = Subscription.builder()
                .user(user).tier(SubscriptionTier.PRO).status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.STRIPE).stripeCustomerId("cus_1")
                .currentPeriodEnd(OffsetDateTime.now(ZoneOffset.UTC).plusDays(20)).build();
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(active));
        when(stripeProperties.priceIdForAlias("pro-monthly")).thenReturn("price_123");

        assertThatThrownBy(() -> service.createCheckout(userId, "pro-monthly"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPortal_noCustomer_throwsBusinessException() {
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        assertThatThrownBy(() -> service.createPortal(userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPortal_withCustomer_returnsUrl() {
        Subscription withCustomer = trialSub().toBuilder().stripeCustomerId("cus_9").build();
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(withCustomer));
        when(stripeGateway.createPortalSession(eq("cus_9"), any())).thenReturn("https://portal/x");

        assertThat(service.createPortal(userId)).isEqualTo("https://portal/x");
    }
}
```

- [ ] **Step 4: Rodar para confirmar falha**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest" 2>&1 | tail -15`
Expected: falha de compilação (`BillingServiceImpl` não existe).

- [ ] **Step 5: Implementar `BillingServiceImpl` (checkout + portal; webhook/activate como stubs por ora)**

`src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java`:
```java
package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.config.StripeProperties;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.ActivateResponse;
import com.piggy.piggyfinance.repository.PasswordResetTokenRepository;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.BillingService;
import com.piggy.piggyfinance.service.stripe.StripeGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillingServiceImpl implements BillingService {

    private final StripeGateway stripeGateway;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final StripeProperties stripeProperties;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Override
    @Transactional
    public String createCheckout(UUID userId, String priceAlias) {
        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("No subscription for user: " + userId));

        String priceId = stripeProperties.priceIdForAlias(priceAlias);
        if (priceId == null) {
            throw new BusinessException("Unknown plan: " + priceAlias);
        }

        if (subscription.getStatus() == SubscriptionStatus.ACTIVE
                && subscription.getSource() == SubscriptionSource.STRIPE) {
            throw new BusinessException("You already have an active subscription. Manage it in the billing portal.");
        }

        User user = subscription.getUser();
        String customerId = subscription.getStripeCustomerId();
        if (customerId == null) {
            customerId = stripeGateway.createCustomer(user.getEmail());
            subscriptionRepository.save(subscription.toBuilder().stripeCustomerId(customerId).build());
        }

        return stripeGateway.createCheckoutSession(
                customerId,
                userId.toString(),
                priceId,
                appBaseUrl + "/assinatura/sucesso?session_id={CHECKOUT_SESSION_ID}",
                appBaseUrl + "/assinatura/cancelado");
    }

    @Override
    public String createPortal(UUID userId) {
        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("No subscription for user: " + userId));
        if (subscription.getStripeCustomerId() == null) {
            throw new BusinessException("No billing account yet. Subscribe first.");
        }
        return stripeGateway.createPortalSession(
                subscription.getStripeCustomerId(), appBaseUrl + "/perfil");
    }

    @Override
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        throw new UnsupportedOperationException("Implemented in Task 8");
    }

    @Override
    @Transactional
    public ActivateResponse activate(String sessionId) {
        throw new UnsupportedOperationException("Implemented in Task 9");
    }
}
```

- [ ] **Step 6: Rodar para confirmar aprovação**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest" 2>&1 | tail -15`
Expected: `5 tests completed, 0 failures`

- [ ] **Step 7: Criar o `BillingController` (checkout + portal)**

`src/main/java/com/piggy/piggyfinance/controller/BillingController.java`:
```java
package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.model.requests.CheckoutRequest;
import com.piggy.piggyfinance.model.responses.CheckoutResponse;
import com.piggy.piggyfinance.model.responses.PortalResponse;
import com.piggy.piggyfinance.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
}
```

- [ ] **Step 8: Compilar (garante controller + service coerentes)**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/model/requests/CheckoutRequest.java \
        src/main/java/com/piggy/piggyfinance/model/requests/ActivateRequest.java \
        src/main/java/com/piggy/piggyfinance/model/responses/CheckoutResponse.java \
        src/main/java/com/piggy/piggyfinance/model/responses/PortalResponse.java \
        src/main/java/com/piggy/piggyfinance/model/responses/ActivateResponse.java \
        src/main/java/com/piggy/piggyfinance/service/BillingService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/BillingController.java \
        src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java
git commit -m "feat: add billing checkout and portal endpoints"
```

---

## Task 8: Webhook — sincronização idempotente do entitlement

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/BillingController.java`
- Modify: `src/main/java/com/piggy/piggyfinance/config/SecurityConfig.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java` (modificar)

**Interfaces:**
- Consumes: `StripeGateway.parseWebhookEvent`, `StripeProperties.tierForPriceId`, `SubscriptionRepository.findByStripeSubscriptionId`/`findByUserId`
- Produces: `POST /api/v1/billing/webhook` público; cada evento mapeado para o estado correto da `subscription` (idempotente)

- [ ] **Step 1: Escrever os testes de webhook**

Adicionar ao `BillingServiceImplTest` os imports:
```java
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
```
Adicionar o captor:
```java
    @Captor ArgumentCaptor<Subscription> subCaptor;
```
Adicionar os testes:
```java
    @Test
    void webhook_checkoutCompleted_activatesSubscriptionForUser() {
        StripeSubscriptionData sub = new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false);
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_1", "checkout.session.completed", userId.toString(), "gab@test.com", sub);
        when(stripeGateway.parseWebhookEvent("payload", "sig")).thenReturn(event);
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);

        service.handleWebhook("payload", "sig");

        verify(subscriptionRepository).save(subCaptor.capture());
        Subscription saved = subCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.getTier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(saved.getSource()).isEqualTo(SubscriptionSource.STRIPE);
        assertThat(saved.getStripeSubscriptionId()).isEqualTo("sub_1");
    }

    @Test
    void webhook_subscriptionUpdatedPastDue_setsPastDue() {
        StripeSubscriptionData sub = new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "past_due",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(5), false);
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_2", "customer.subscription.updated", null, null, sub);
        when(stripeGateway.parseWebhookEvent("p", "s")).thenReturn(event);
        Subscription existing = trialSub().toBuilder()
                .status(SubscriptionStatus.ACTIVE).source(SubscriptionSource.STRIPE)
                .stripeSubscriptionId("sub_1").build();
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(existing));
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);

        service.handleWebhook("p", "s");

        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void webhook_subscriptionDeleted_setsCanceled() {
        StripeSubscriptionData sub = new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "canceled", null, false);
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_3", "customer.subscription.deleted", null, null, sub);
        when(stripeGateway.parseWebhookEvent("p", "s")).thenReturn(event);
        Subscription existing = trialSub().toBuilder()
                .status(SubscriptionStatus.ACTIVE).source(SubscriptionSource.STRIPE)
                .stripeSubscriptionId("sub_1").build();
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(existing));

        service.handleWebhook("p", "s");

        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    void webhook_unhandledType_isIgnored() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_4", "customer.created", null, null, null);
        when(stripeGateway.parseWebhookEvent("p", "s")).thenReturn(event);

        service.handleWebhook("p", "s");

        verify(subscriptionRepository, never()).save(any());
    }
```

- [ ] **Step 2: Rodar para confirmar falha**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest" 2>&1 | tail -15`
Expected: falha — `handleWebhook` ainda lança `UnsupportedOperationException`.

- [ ] **Step 3: Implementar `handleWebhook` em `BillingServiceImpl`**

Substituir o corpo do método `handleWebhook` (o stub) por:
```java
    @Override
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        StripeWebhookEvent event = stripeGateway.parseWebhookEvent(payload, signatureHeader);
        log.info("Processing Stripe webhook {} ({})", event.id(), event.type());

        switch (event.type()) {
            case "checkout.session.completed" -> applyCheckoutCompleted(event);
            case "customer.subscription.updated" -> applyStatusFromStripe(event);
            case "customer.subscription.deleted" -> applyCanceled(event);
            case "invoice.payment_failed" -> applyPastDue(event);
            default -> log.debug("Ignoring unhandled Stripe event type: {}", event.type());
        }
    }

    private void applyCheckoutCompleted(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;
        Subscription subscription = event.clientReferenceId() != null
                ? subscriptionRepository.findByUserId(UUID.fromString(event.clientReferenceId())).orElse(null)
                : subscriptionRepository.findByStripeCustomerId(sub.customerId()).orElse(null);
        if (subscription == null) {
            log.warn("checkout.session.completed for unknown user (event {})", event.id());
            return;
        }
        subscriptionRepository.save(subscription.toBuilder()
                .tier(tierFor(sub))
                .status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.STRIPE)
                .stripeCustomerId(sub.customerId())
                .stripeSubscriptionId(sub.subscriptionId())
                .currentPeriodEnd(sub.currentPeriodEnd())
                .cancelAtPeriodEnd(sub.cancelAtPeriodEnd())
                .build());
    }

    private void applyStatusFromStripe(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;
        subscriptionRepository.findByStripeSubscriptionId(sub.subscriptionId()).ifPresent(s ->
                subscriptionRepository.save(s.toBuilder()
                        .tier(tierFor(sub))
                        .status(mapStatus(sub.status()))
                        .currentPeriodEnd(sub.currentPeriodEnd())
                        .cancelAtPeriodEnd(sub.cancelAtPeriodEnd())
                        .build()));
    }

    private void applyCanceled(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;
        subscriptionRepository.findByStripeSubscriptionId(sub.subscriptionId()).ifPresent(s ->
                subscriptionRepository.save(s.toBuilder().status(SubscriptionStatus.CANCELED).build()));
    }

    private void applyPastDue(StripeWebhookEvent event) {
        StripeSubscriptionData sub = event.subscription();
        if (sub == null) return;
        subscriptionRepository.findByStripeSubscriptionId(sub.subscriptionId()).ifPresent(s ->
                subscriptionRepository.save(s.toBuilder().status(SubscriptionStatus.PAST_DUE).build()));
    }

    private com.piggy.piggyfinance.enums.SubscriptionTier tierFor(StripeSubscriptionData sub) {
        com.piggy.piggyfinance.enums.SubscriptionTier tier = stripeProperties.tierForPriceId(sub.priceId());
        return tier != null ? tier : com.piggy.piggyfinance.enums.SubscriptionTier.ESSENCIAL;
    }

    private SubscriptionStatus mapStatus(String stripeStatus) {
        if (stripeStatus == null) return SubscriptionStatus.PAST_DUE;
        return switch (stripeStatus) {
            case "active" -> SubscriptionStatus.ACTIVE;
            case "trialing" -> SubscriptionStatus.TRIALING;
            case "past_due" -> SubscriptionStatus.PAST_DUE;
            case "canceled", "unpaid", "incomplete_expired" -> SubscriptionStatus.CANCELED;
            default -> SubscriptionStatus.PAST_DUE;
        };
    }
```

Adicionar os imports necessários no topo do `BillingServiceImpl`:
```java
import com.piggy.piggyfinance.service.stripe.dto.StripeSubscriptionData;
import com.piggy.piggyfinance.service.stripe.dto.StripeWebhookEvent;
```
(`java.util.UUID` já está importado.)

> Idempotência: cada handler grava **estado absoluto** (status/tier/período), então reprocessar o mesmo evento produz o mesmo resultado; `checkout.session.completed` é ainda protegido pela unicidade de `stripe_subscription_id`. Não é preciso tabela de eventos processados.

- [ ] **Step 4: Rodar para confirmar aprovação**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest" 2>&1 | tail -15`
Expected: todos os testes passam (`0 failures`).

- [ ] **Step 5: Adicionar o endpoint de webhook ao controller**

Em `src/main/java/com/piggy/piggyfinance/controller/BillingController.java`, adicionar o import:
```java
import org.springframework.web.bind.annotation.RequestHeader;
```
E o método:
```java
    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void webhook(@RequestBody String payload,
                        @RequestHeader("Stripe-Signature") String signature) {
        billingService.handleWebhook(payload, signature);
    }
```

- [ ] **Step 6: Tornar o webhook público no `SecurityConfig`**

Em `src/main/java/com/piggy/piggyfinance/config/SecurityConfig.java`, dentro de `authorizeHttpRequests`, adicionar as duas linhas **antes** de `.anyRequest().authenticated()`:
```java
                        .requestMatchers(HttpMethod.POST, "/api/v1/billing/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/billing/activate").permitAll()
```
(`HttpMethod` já é importado no arquivo.)

- [ ] **Step 7: Compilar**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/BillingController.java \
        src/main/java/com/piggy/piggyfinance/config/SecurityConfig.java \
        src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java
git commit -m "feat: handle Stripe webhooks with idempotent entitlement sync"
```

---

## Task 9: Activate — pagar na LP antes de ter conta

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/BillingController.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java` (modificar)

**Interfaces:**
- Consumes: `StripeGateway.retrieveCheckoutSession`, `UserRepository.findByEmail`/`save`, `PasswordResetTokenRepository.save`/`markAllUnusedByUserIdAsUsed`, `StripeProperties.tierForPriceId`
- Produces: `POST /api/v1/billing/activate` público → `ActivateResponse(setupToken, email)`; cria/vincula conta e assinatura de forma idempotente

- [ ] **Step 1: Escrever os testes de activate**

Adicionar ao `BillingServiceImplTest` os imports:
```java
import com.piggy.piggyfinance.model.PasswordResetToken;
import com.piggy.piggyfinance.model.responses.ActivateResponse;
import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
```
Adicionar os testes:
```java
    @Test
    void activate_newEmail_createsUserSubscriptionAndSetupToken() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_1", "cus_1", "sub_1", null, "novo@test.com", true);
        when(stripeGateway.retrieveCheckoutSession("cs_1")).thenReturn(checkout);
        when(stripeGateway.retrieveSubscription("sub_1")).thenReturn(new StripeSubscriptionData(
                "sub_1", "cus_1", "price_pro", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false));
        when(stripeProperties.tierForPriceId("price_pro")).thenReturn(SubscriptionTier.PRO);
        when(userRepository.findByEmail("novo@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("randomhash");
        User createdUser = User.builder().id(UUID.randomUUID()).name("novo").email("novo@test.com")
                .password("randomhash").createdAt(LocalDateTime.now()).build();
        when(userRepository.save(any(User.class))).thenReturn(createdUser);
        when(subscriptionRepository.findByUserId(createdUser.getId())).thenReturn(Optional.empty());

        ActivateResponse resp = service.activate("cs_1");

        assertThat(resp.email()).isEqualTo("novo@test.com");
        assertThat(resp.setupToken()).isNotBlank();
        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subCaptor.getValue().getTier()).isEqualTo(SubscriptionTier.PRO);
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void activate_unpaidSession_throwsBusinessException() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_2", "cus_2", "sub_2", null, "x@test.com", false);
        when(stripeGateway.retrieveCheckoutSession("cs_2")).thenReturn(checkout);

        assertThatThrownBy(() -> service.activate("cs_2"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void activate_existingEmail_linksSubscriptionWithoutCreatingUser() {
        StripeCheckoutData checkout = new StripeCheckoutData(
                "cs_3", "cus_3", "sub_3", null, "gab@test.com", true);
        when(stripeGateway.retrieveCheckoutSession("cs_3")).thenReturn(checkout);
        when(stripeGateway.retrieveSubscription("sub_3")).thenReturn(new StripeSubscriptionData(
                "sub_3", "cus_3", "price_ess", "active",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), false));
        when(stripeProperties.tierForPriceId("price_ess")).thenReturn(SubscriptionTier.ESSENCIAL);
        when(userRepository.findByEmail("gab@test.com")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(trialSub()));

        ActivateResponse resp = service.activate("cs_3");

        assertThat(resp.email()).isEqualTo("gab@test.com");
        verify(userRepository, never()).save(any(User.class));
        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getTier()).isEqualTo(SubscriptionTier.ESSENCIAL);
    }
```

> Nota: confirmar que `PasswordResetToken` tem builder com `user`, `token`, `expiresAt`, `used` (é o caso — ver `model/PasswordResetToken.java`) e que `PasswordResetTokenRepository.markAllUnusedByUserIdAsUsed(UUID)` existe.

- [ ] **Step 2: Rodar para confirmar falha**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest" 2>&1 | tail -15`
Expected: falha — `activate` ainda lança `UnsupportedOperationException`.

- [ ] **Step 3: Implementar `activate` em `BillingServiceImpl`**

Adicionar imports no topo:
```java
import com.piggy.piggyfinance.model.PasswordResetToken;
import com.piggy.piggyfinance.service.stripe.dto.StripeCheckoutData;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
```

Substituir o corpo do método `activate` (o stub) por:
```java
    private static final int SETUP_TOKEN_EXPIRY_MINUTES = 30;

    @Override
    @Transactional
    public ActivateResponse activate(String sessionId) {
        StripeCheckoutData checkout = stripeGateway.retrieveCheckoutSession(sessionId);
        if (!checkout.paid()) {
            throw new BusinessException("Checkout session is not paid");
        }
        if (checkout.customerEmail() == null) {
            throw new BusinessException("Checkout session has no email");
        }

        StripeSubscriptionData sub = stripeGateway.retrieveSubscription(checkout.subscriptionId());
        var tier = tierFor(sub);

        User user = userRepository.findByEmail(checkout.customerEmail()).orElseGet(() -> {
            String localPart = checkout.customerEmail().split("@")[0];
            return userRepository.save(User.builder()
                    .name(localPart)
                    .email(checkout.customerEmail())
                    .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .createdAt(LocalDateTime.now())
                    .build());
        });

        Subscription existing = subscriptionRepository.findByUserId(user.getId()).orElse(null);
        Subscription.SubscriptionBuilder builder = existing != null
                ? existing.toBuilder()
                : Subscription.builder().user(user).cancelAtPeriodEnd(false);

        subscriptionRepository.save(builder
                .tier(tier)
                .status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.STRIPE)
                .stripeCustomerId(checkout.customerId())
                .stripeSubscriptionId(checkout.subscriptionId())
                .currentPeriodEnd(sub.currentPeriodEnd())
                .cancelAtPeriodEnd(sub.cancelAtPeriodEnd())
                .build());

        passwordResetTokenRepository.markAllUnusedByUserIdAsUsed(user.getId());
        String setupToken = UUID.randomUUID().toString();
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .token(setupToken)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(SETUP_TOKEN_EXPIRY_MINUTES))
                .used(false)
                .build());

        log.info("Activated subscription via LP for user {}", user.getId());
        return new ActivateResponse(setupToken, user.getEmail());
    }
```

> O `setupToken` é um `PasswordResetToken` — o frontend usa o fluxo existente `POST /api/auth/reset-password` (`{ token, newPassword }`) para o usuário definir a senha e então logar. Reuso deliberado; sem novo mecanismo de token.

- [ ] **Step 4: Rodar para confirmar aprovação**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest" 2>&1 | tail -15`
Expected: todos os testes passam (`0 failures`).

- [ ] **Step 5: Adicionar o endpoint de activate ao controller**

Em `src/main/java/com/piggy/piggyfinance/controller/BillingController.java`, adicionar imports:
```java
import com.piggy.piggyfinance.model.requests.ActivateRequest;
import com.piggy.piggyfinance.model.responses.ActivateResponse;
```
E o método:
```java
    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.OK)
    public ActivateResponse activate(@RequestBody @Valid ActivateRequest request) {
        return billingService.activate(request.sessionId());
    }
```

- [ ] **Step 6: Compilar**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/BillingController.java \
        src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java
git commit -m "feat: add LP pay-before-account activation flow"
```

---

## Task 10: Job de expiração + rate limit + build final

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/job/SubscriptionExpiryJob.java`
- Modify: `src/main/java/com/piggy/piggyfinance/config/RateLimitFilter.java`
- Test: `src/test/java/com/piggy/piggyfinance/job/SubscriptionExpiryJobTest.java`

**Interfaces:**
- Consumes: `SubscriptionRepository.findExpired`/`save` (Task 1)
- Produces: sweep diário que materializa trials/assinaturas vencidos em `EXPIRED / FREE`; `activate` protegido por rate limit

- [ ] **Step 1: Escrever o teste do job**

`src/test/java/com/piggy/piggyfinance/job/SubscriptionExpiryJobTest.java`:
```java
package com.piggy.piggyfinance.job;

import com.piggy.piggyfinance.enums.SubscriptionSource;
import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionExpiryJobTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @InjectMocks SubscriptionExpiryJob job;
    @Captor ArgumentCaptor<Subscription> captor;

    @Test
    void expireStale_flipsExpiredToFree() {
        Subscription expiredTrial = Subscription.builder()
                .tier(SubscriptionTier.PRO).status(SubscriptionStatus.TRIALING)
                .source(SubscriptionSource.INTERNAL)
                .trialEndsAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)).build();
        when(subscriptionRepository.findExpired(any())).thenReturn(List.of(expiredTrial));

        job.expireStale();

        verify(subscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(captor.getValue().getTier()).isEqualTo(SubscriptionTier.FREE);
    }
}
```

- [ ] **Step 2: Rodar para confirmar falha**

Run: `./gradlew test --tests "com.piggy.piggyfinance.job.SubscriptionExpiryJobTest" 2>&1 | tail -15`
Expected: falha de compilação (`SubscriptionExpiryJob` não existe).

- [ ] **Step 3: Criar `SubscriptionExpiryJob`**

`src/main/java/com/piggy/piggyfinance/job/SubscriptionExpiryJob.java`:
```java
package com.piggy.piggyfinance.job;

import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.model.Subscription;
import com.piggy.piggyfinance.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryJob {

    private final SubscriptionRepository subscriptionRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void expireStale() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Subscription s : subscriptionRepository.findExpired(now)) {
            subscriptionRepository.save(s.toBuilder()
                    .status(SubscriptionStatus.EXPIRED)
                    .tier(SubscriptionTier.FREE)
                    .build());
            log.info("Expired subscription {} downgraded to FREE", s.getId());
        }
    }
}
```

- [ ] **Step 4: Rodar para confirmar aprovação**

Run: `./gradlew test --tests "com.piggy.piggyfinance.job.SubscriptionExpiryJobTest" 2>&1 | tail -15`
Expected: `1 test completed, 0 failures`

- [ ] **Step 5: Adicionar `activate` ao `RateLimitFilter`**

Em `src/main/java/com/piggy/piggyfinance/config/RateLimitFilter.java`, alterar a lista `RATE_LIMITED_PATHS` para incluir o novo path:
```java
    private static final List<String> RATE_LIMITED_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/forgot-password",
            "/api/v1/users/whatsapp/link/confirm",
            "/api/v1/billing/activate"
    );
```

- [ ] **Step 6: Build completo (toda a suíte)**

Run: `./gradlew build 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL` — todos os testes das Tasks 1–10 passam.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/job/SubscriptionExpiryJob.java \
        src/main/java/com/piggy/piggyfinance/config/RateLimitFilter.java \
        src/test/java/com/piggy/piggyfinance/job/SubscriptionExpiryJobTest.java
git commit -m "feat: add daily subscription-expiry sweep and rate-limit activation endpoint"
```

---

## Notas de deploy (fora do código, para o lançamento)

Variáveis de ambiente novas a configurar no ambiente (não versionar):
- `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`
- `STRIPE_PRICE_ESSENCIAL_MONTHLY`, `STRIPE_PRICE_ESSENCIAL_ANNUAL`, `STRIPE_PRICE_PRO_MONTHLY`, `STRIPE_PRICE_PRO_ANNUAL`

No dashboard do Stripe: criar os 2 produtos (Essencial, Pro) com preços mensal e anual; registrar o endpoint de webhook (`{host}/api/v1/billing/webhook`) assinando os eventos `checkout.session.completed`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_failed`; habilitar o Customer Portal. Testar webhooks com o Stripe CLI (`stripe listen --forward-to localhost:8080/api/v1/billing/webhook`).
