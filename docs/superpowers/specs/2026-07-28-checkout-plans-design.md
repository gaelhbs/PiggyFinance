# Checkout & Planos — Design Spec

**Status:** Aprovado (design) — pronto para virar plano de implementação
**Data:** 2026-07-28
**Escopo deste spec:** Backend-only (Spring Boot). O frontend (repo Flutter) será um spec próprio na sequência.
**Repo:** `/Users/gabrielbraga/Documents/Projects/java/PiggyFinance`

---

## 1. Objetivo

Introduzir monetização por assinatura no PiggyFinance com três níveis de acesso (Free, Essencial, Pro), um trial de 7 dias, e integração com o **Stripe** como provedor de pagamento. O acesso pago é vendido principalmente por uma landing page (LP) externa, mas o app também aceita cadastro orgânico.

O princípio arquitetural central é **separar *entitlement* (direito de acesso) do *canal de pagamento***: o app e o fluxo n8n nunca consultam o Stripe diretamente — perguntam ao nosso backend qual o tier vigente de um usuário. O Stripe é apenas a primeira fonte que alimenta esse entitlement. Isso mantém o caminho futuro para App Store / Play Store (Apple IAP, Google Play Billing) como uma **adição de fonte**, não uma reescrita.

### Não-objetivos (fora deste spec)

- Frontend / telas de paywall, checkout e ativação (spec próprio depois).
- Integração com Apple IAP / Google Play Billing — o modelo de dados prevê o campo `source`, mas nenhuma implementação de loja é feita agora.
- Pagamento por Pix (Stripe não suporta Pix recorrente; recorrência é por cartão).
- A "inteligência" do fluxo n8n em si (subsistema separado). Aqui apenas expomos o *gate* de Pro e o tier vigente.

---

## 2. Modelo de produto

Três tiers permanentes. A IA do WhatsApp é exclusiva do Pro.

| Recurso | Free | Essencial | Pro |
|---|:---:|:---:|:---:|
| Ver histórico (read) | ✅ | ✅ | ✅ |
| Criar transações no app | ≤ 15 / mês | ilimitado | ilimitado |
| Metas (total) | 1 | ilimitado | ilimitado |
| Relatórios | ❌ | ✅ | ✅ |
| IA no WhatsApp | ❌ | ❌ | ✅ |

### Estados de entrada

- **Cadastro direto (orgânico):** ao registrar, o usuário nasce **Pro em trial** por 7 dias. Ao fim do trial, sem assinatura ativa, o tier vigente resolve para **Free**.
- **LP paga:** o usuário paga na LP (Stripe Checkout) antes ou depois de ter conta; a assinatura ativa o tier comprado (Essencial ou Pro) imediatamente, ignorando o trial.

### Cobrança

- Intervalos no lançamento: **mensal e anual** para cada tier pago (4 Price IDs no Stripe: `essencial_monthly`, `essencial_annual`, `pro_monthly`, `pro_annual`).
- Recorrência por cartão. Anual com desconto (definido no Stripe, não no código).

---

## 3. Modelo de dados

### Nova tabela: `subscriptions` (migration `V9__subscriptions.sql`)

Fonte da verdade do acesso. Uma linha por usuário (relação 1:1; o registro nasce no cadastro).

```sql
CREATE TABLE subscriptions (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    tier                   VARCHAR(20)  NOT NULL,   -- FREE | ESSENCIAL | PRO
    status                 VARCHAR(20)  NOT NULL,   -- TRIALING | ACTIVE | PAST_DUE | CANCELED | EXPIRED
    source                 VARCHAR(20)  NOT NULL,   -- INTERNAL | STRIPE | APPLE | GOOGLE
    stripe_customer_id     VARCHAR(255),
    stripe_subscription_id VARCHAR(255) UNIQUE,
    trial_ends_at          TIMESTAMPTZ,
    current_period_end     TIMESTAMPTZ,
    cancel_at_period_end   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_user_id          ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_stripe_customer  ON subscriptions(stripe_customer_id);
```

### Enums (novos, em `enums/`)

- `SubscriptionTier { FREE, ESSENCIAL, PRO }`
- `SubscriptionStatus { TRIALING, ACTIVE, PAST_DUE, CANCELED, EXPIRED }`
- `SubscriptionSource { INTERNAL, STRIPE, APPLE, GOOGLE }`

### Entidade `Subscription` (`model/Subscription.java`)

JPA `@Entity` mapeando a tabela acima, seguindo o padrão das entidades existentes (Lombok `@Getter`/`@Builder(toBuilder = true)`, construtores protegidos, `@PrePersist`/`@PreUpdate` para `createdAt`/`updatedAt`). `@OneToOne(fetch = LAZY)` para `User`.

**Tipo de tempo:** os campos temporais (`trial_ends_at`, `current_period_end`, `created_at`, `updated_at`) usam `OffsetDateTime` na entidade e nos parâmetros de query, mapeando para `TIMESTAMPTZ` — mesmo padrão da entidade mais recente sensível a tempo, `PasswordResetToken` (V8). Manter esse tipo de forma consistente entre entidade e repositório (o parâmetro `now` de `findExpired` também é `OffsetDateTime`).

### Repositório `SubscriptionRepository`

```java
Optional<Subscription> findByUserId(UUID userId);
Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);
Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

// Job de expiração: trials/assinaturas vencidos que ainda não caíram para FREE/EXPIRED
@Query("SELECT s FROM Subscription s WHERE s.status IN ('TRIALING','ACTIVE','PAST_DUE') " +
       "AND ((s.status = 'TRIALING' AND s.trialEndsAt < :now) " +
       "  OR (s.status IN ('ACTIVE','PAST_DUE') AND s.currentPeriodEnd < :now))")
List<Subscription> findExpired(@Param("now") OffsetDateTime now);
```

---

## 4. Resolução de entitlement

`EntitlementService` é o único ponto que responde "qual o tier vigente deste usuário?". Duas responsabilidades:

### 4.1 Resolução lazy (`getEffectiveTier(UUID userId) → SubscriptionTier`)

Regra pura, sem efeito colateral, robusta a status desatualizado no banco:

1. Carrega a `subscription` do usuário. Se não existir (usuário legado, pré-migração) → `FREE`.
2. Se `status == TRIALING` e `trial_ends_at > now` → retorna `tier` (será `PRO`).
3. Se `status == ACTIVE` e (`current_period_end` é null **ou** `> now`) → retorna `tier`.
4. Se `status == PAST_DUE` e `current_period_end > now` → retorna `tier` (período de graça até o fim do ciclo pago).
5. Qualquer outro caso (trial vencido, cancelado, expirado, past_due vencido) → `FREE`.

Métodos de conveniência: `hasAtLeast(userId, tier)` (ordena FREE < ESSENCIAL < PRO), `requireTier(userId, tier)` (lança `FeatureLockedException` se não atende).

### 4.2 Sweep agendado (`@Scheduled` diário)

Um job (`SubscriptionExpiryJob`, cron diário, ex. 03:00) chama `SubscriptionRepository.findExpired(now)` e materializa os estados: trial vencido → `status = EXPIRED, tier = FREE`; assinatura paga vencida sem renovação → `status = EXPIRED, tier = FREE`. Isso mantém o banco honesto para relatórios/consultas; a resolução lazy garante correção mesmo se o job não tiver rodado. Requer `@EnableScheduling` na aplicação.

### 4.3 Concessão de trial no cadastro

Em `AuthServiceImpl.register`, após salvar o `User`, cria a `subscription`:
`tier = PRO, status = TRIALING, source = INTERNAL, trial_ends_at = now + 7 dias`. O trial é concedido **uma única vez** por usuário — a linha nasce junto com a conta e nunca é recriada, então não há re-grant.

---

## 5. Integração Stripe

Nova dependência: `com.stripe:stripe-java` (SDK oficial) no `build.gradle`. Config em `application.yml` (seção 8).

Quatro endpoints sob `/api/v1/billing`, servidos por `BillingController`, com lógica em `BillingService` / `StripeService`.

### 5.1 `POST /api/v1/billing/checkout` (autenticado)

Body: `{ "priceId": "<stripe_price_id>" }` (ou um alias como `"pro_annual"` resolvido para o Price ID via config — preferir alias para não expor IDs no frontend).

- Garante um Stripe Customer para o usuário (cria e persiste `stripe_customer_id` se ainda não houver).
- Cria uma Checkout Session `mode = subscription` com:
  - `client_reference_id = userId`
  - `customer = stripe_customer_id`
  - `line_items = [{ price, quantity: 1 }]`
  - `success_url = {app.base-url}/assinatura/sucesso?session_id={CHECKOUT_SESSION_ID}`
  - `cancel_url = {app.base-url}/assinatura/cancelado`
- Retorna `{ "url": "<checkout_url>" }`. O frontend redireciona.
- Se o usuário já tem assinatura paga ativa → retorna erro de negócio orientando usar o portal (evita assinatura duplicada).

Usado tanto pelo botão "upgrade" no app web quanto pela LP (a LP pode chamar este endpoint autenticado após login, ou usar o fluxo pré-conta da seção 5.4).

### 5.2 `POST /api/v1/billing/webhook` (público, assinado)

Endpoint público (sem JWT) que verifica a assinatura do Stripe (`Stripe-Signature` + `webhook secret`). **Idempotente**: deduplica por `event.id` (rejeita reprocessamento) e faz upsert idempotente da `subscription`.

Eventos tratados:

| Evento | Ação na `subscription` |
|---|---|
| `checkout.session.completed` | Vincula `stripe_customer_id`/`stripe_subscription_id`; resolve tier a partir do Price; `status = ACTIVE`; grava `current_period_end`. Resolve o usuário por `client_reference_id`; se ausente (fluxo pré-conta), por `customer`/email (ver 5.4). |
| `customer.subscription.updated` | Sincroniza `status`, `current_period_end`, `cancel_at_period_end`, e `tier` (se mudou de plano). |
| `customer.subscription.deleted` | `status = CANCELED`; a resolução lazy passa a devolver `FREE` ao fim do período. |
| `invoice.payment_failed` | `status = PAST_DUE`. |

Mapeamento Price ID → (tier, intervalo) vem da config (seção 8), não hardcoded.

### 5.3 `POST /api/v1/billing/portal` (autenticado)

Cria uma sessão do Stripe Customer Portal para o `stripe_customer_id` do usuário e retorna `{ "url": ... }`. Toda gestão (trocar cartão, cancelar, ver faturas) acontece no portal hospedado do Stripe — não construímos essas telas.

### 5.4 `POST /api/v1/billing/activate` (público) — pagar na LP antes de ter conta

Resolve o funil "LP paga → cria conta depois". Body: `{ "sessionId": "<checkout_session_id>" }`.

1. Busca a Checkout Session **direto na API do Stripe** (fonte da verdade — não depende do timing do webhook).
2. Extrai `customer`, `subscription`, email e o Price (tier).
3. Upsert **idempotente** do usuário por email:
   - Se não existe usuário com aquele email → cria um usuário provisório (email, sem senha ainda) e a `subscription` ativa.
   - Se já existe → apenas vincula/atualiza a `subscription`.
4. Retorna um **token curto de conclusão de cadastro** (single-use, ~30 min) para o frontend abrir a tela "defina sua senha". Após definir a senha, o usuário faz login normal.

O webhook `checkout.session.completed` e o `activate` convergem no **mesmo upsert idempotente** — quem chegar primeiro cria; o segundo apenas confirma. Isso elimina a corrida entre "usuário voltou do Stripe" e "webhook chegou".

> Observação de segurança: como cria conta a partir de um `sessionId`, o endpoint valida que a sessão está `paid`/`complete` e ainda não foi consumida por outra ativação (deduplicação por `stripe_subscription_id UNIQUE`).

---

## 6. Gating (aplicação das regras)

Os pontos protegidos chamam `EntitlementService`. Nenhuma regra de tier fica espalhada — todas passam por ele.

| Regra | Onde | Comportamento se não atende |
|---|---|---|
| IA WhatsApp exige **Pro** | `TransactionServiceImpl.createWhatsAppTransaction` (após resolver o usuário por telefone) | Lança `FeatureLockedException(PRO)` → `402`, corpo com `requiredTier`, para o n8n responder "recurso Pro" |
| Relatórios exigem **Essencial+** | endpoints/serviço de relatórios (subsistema futuro) — o gate já fica disponível via `requireTier` | `402 FEATURE_LOCKED` |
| Transação no app: Free ≤ 15/mês | `TransactionServiceImpl.createTransaction` quando `source = APP` e tier resolvido = `FREE` | Conta transações do mês corrente do usuário; se ≥ 15 → `402 FEATURE_LOCKED` com mensagem de upgrade |
| Metas: Free ≤ 1 (total) | `GoalServiceImpl` na criação, quando tier = `FREE` | Se o usuário já tem ≥ 1 meta → `402 FEATURE_LOCKED` |

- Contagem mensal de transações: nova query em `TransactionRepository` (`countByUserIdAndTimestampBetween` ou equivalente para o mês corrente).
- Contagem de metas: query `countByUserId` em `GoalRepository`. **Definição:** o limite do Free é sobre o **total de metas do usuário** (qualquer linha em `goals` conta) — não há noção de meta "ativa/concluída" porque a tabela `goals` (V7) não tem coluna de status, e adicioná-la está fora de escopo. Concluir uma meta **não** libera o slot; para criar outra, o usuário do Free precisa excluir a existente ou fazer upgrade.
- O gate de Pro no WhatsApp roda **depois** da resolução do usuário por telefone e **antes** de persistir a transação.

### Novo exception + handler

`FeatureLockedException extends RuntimeException` carregando o `requiredTier`. Novo handler em `GlobalExceptionHandler` → HTTP **402 Payment Required**. O corpo usa uma resposta de erro **estruturada** com um campo `requiredTier` legível por máquina (ex.: novo record `FeatureLockedResponse(code, message, requiredTier)` ou um campo adicional dedicado) — **não** dobrar o tier dentro da string `message`, porque o fluxo n8n precisa ler `requiredTier` programaticamente para responder "recurso Pro". O `ErrorResponse` de 3 campos existente permanece para os demais erros.

---

## 7. Mudanças de segurança / rotas públicas

Em `SecurityConfig`, tornar públicos (sem JWT) apenas:

- `POST /api/v1/billing/webhook` — autenticado pela assinatura do Stripe, não por JWT.
- `POST /api/v1/billing/activate` — o usuário ainda não tem sessão.

`checkout` e `portal` permanecem sob `anyRequest().authenticated()`. O webhook lê o corpo bruto para verificação de assinatura (garantir acesso ao raw body — Stripe SDK usa o payload cru + header).

Rate limit: adicionar `/api/v1/billing/activate` à lista de paths limitados do `RateLimitFilter` (endpoint público que cria contas). O webhook **não** entra no rate limit (Stripe pode legitimamente disparar rajadas).

---

## 8. Configuração

Adicionar ao `application.yml`:

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

Um `@ConfigurationProperties("stripe")` expõe as chaves e o mapa de prices (alias ↔ Price ID ↔ tier/intervalo), usado por checkout (alias → Price ID) e webhook (Price ID → tier).

---

## 9. Tratamento de erros / casos de borda

- **Idempotência do webhook:** dedupe por `event.id`; upsert por `stripe_subscription_id`. Reprocessamento não duplica nem corrompe estado.
- **Corrida activate × webhook:** convergem no mesmo upsert idempotente (seção 5.4).
- **Assinatura duplicada:** `checkout` recusa se já há assinatura paga ativa; orienta ao portal.
- **Falha de pagamento:** `PAST_DUE` mantém acesso até o fim do período pago (graça), depois cai para `FREE`.
- **Assinatura do webhook inválida:** `400`, sem processar.
- **Anti-abuso de trial:** trial só existe na criação da conta; nunca é re-concedido.
- **Usuários legados (pré-migração):** sem linha em `subscriptions` → resolvem para `FREE`. (O plano pode incluir um backfill opcional concedendo Free explícito; não obrigatório para correção.)

---

## 10. Testes

Unitários (Mockito, seguindo o padrão dos testes existentes em `service/`):

- **`EntitlementServiceTest`** — matriz da resolução lazy: trial válido → PRO; trial vencido → FREE; ACTIVE sem período → tier; ACTIVE vencido → FREE; PAST_DUE dentro/fora do período; CANCELED → FREE; sem subscription → FREE. `hasAtLeast`/`requireTier`.
- **`BillingServiceTest` / webhook handler** — cada evento (`checkout.session.completed`, `subscription.updated`, `subscription.deleted`, `invoice.payment_failed`) produz o estado correto; idempotência (mesmo `event.id` duas vezes → um único efeito); mapeamento Price → tier.
- **`activate`** — idempotência (webhook antes/depois), sessão não paga rejeitada, reuso de `stripe_subscription_id` bloqueado.
- **Gating** — `createWhatsAppTransaction` não-Pro lança `FeatureLockedException`; Free no 16º/mês bloqueia; Free com 1 meta existente bloqueia a criação da 2ª; Essencial/Pro passam.
- **`register`** — cria subscription `PRO/TRIALING/INTERNAL` com `trial_ends_at ≈ now+7d`.

Stripe é mockado nos unitários (o SDK é isolado atrás de `StripeService`/gateway para permitir stub). Teste manual em **Stripe test mode** com o Stripe CLI para os webhooks.

---

## 11. Caminho futuro (fora deste spec, mas garantido pelo design)

- **App Store / Play Store:** adicionar `SubscriptionSource.APPLE`/`GOOGLE` como novas fontes que gravam na mesma `subscriptions` (via App Store Server Notifications / Google RTDN). Nenhuma mudança em `EntitlementService`, gating, app ou n8n. Regra de ouro mantida: **nenhum fluxo de compra dentro do app nativo** — web usa Stripe; lojas usarão IAP.
- **RevenueCat** pode ser reavaliado como camada unificadora *quando* as lojas entrarem; não é necessário agora.
- **Relatórios** e **melhorias do fluxo n8n** são subsistemas próprios; este spec apenas expõe o gate de tier que eles consumirão.

---

## Arquivos afetados (resumo)

**Novos:**
- `db/migration/V9__subscriptions.sql`
- `enums/SubscriptionTier.java`, `enums/SubscriptionStatus.java`, `enums/SubscriptionSource.java`
- `model/Subscription.java`
- `repository/SubscriptionRepository.java`
- `service/EntitlementService.java` + `service/impl/EntitlementServiceImpl.java`
- `service/BillingService.java` + `service/impl/BillingServiceImpl.java` (+ `StripeService`/gateway)
- `controller/BillingController.java`
- `config/StripeProperties.java` (`@ConfigurationProperties`)
- `job/SubscriptionExpiryJob.java`
- `exceptions/FeatureLockedException.java`
- DTOs de request/response de billing (checkout, activate, portal)
- Testes correspondentes em `src/test/.../service/`

**Modificados:**
- `build.gradle` — `stripe-java`
- `application.yml` — seção `stripe`
- `PiggyFinanceApplication.java` — `@EnableScheduling`
- `service/impl/AuthServiceImpl.java` — cria subscription de trial no register
- `service/impl/TransactionServiceImpl.java` — gate Pro no WhatsApp + limite Free/mês no app
- `service/impl/GoalServiceImpl.java` — limite de 1 meta no Free
- `repository/TransactionRepository.java` — contagem mensal
- `repository/GoalRepository.java` — contagem de metas ativas
- `config/SecurityConfig.java` — rotas públicas de billing
- `config/RateLimitFilter.java` — inclui `activate`
- `exceptions/handler/GlobalExceptionHandler.java` — handler `FeatureLockedException` → 402