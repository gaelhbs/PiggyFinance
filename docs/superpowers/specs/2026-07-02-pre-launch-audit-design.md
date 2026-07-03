# PiggyFinance — Pre-Launch Audit & Fixes (Abordagem A)

**Data:** 2026-07-02  
**Escopo:** Correções bloqueadoras para lançamento como produto real com dados financeiros de usuários reais.  
**Repositórios envolvidos:**
- Backend: `/java/PiggyFinance` (Spring Boot 4.0.2, Java 21, PostgreSQL)
- Frontend: `/flutter/piggyapp` (React 18, Vite, TypeScript, Tailwind/shadcn)

---

## 1. Contexto

O PiggyFinance está em produção em `piggyfinance.cloud` com infraestrutura Hostinger + Traefik + Docker. Uma auditoria de segurança anterior (Semgrep + OWASP ZAP) foi realizada com 4 correções implementadas no código mas nunca commitadas. Esta spec cobre:

1. Os fixes pendentes + vulnerabilidades remanescentes (backend)
2. Funcionalidades obrigatórias para produto real (recuperação de senha, exclusão de conta)
3. Problemas críticos de arquitetura e UX no frontend

---

## 2. Backend — Problemas e Decisões

### 2.1 Fixes de segurança não commitados

Os seguintes arquivos têm mudanças no working tree que não foram commitadas:

| Arquivo | Fix |
|---|---|
| `AuthServiceImpl.java` | Remove PII (email) de logs; remove email da mensagem de erro (email enumeration) |
| `ApiKeyAuthFilter.java` | Troca `.equals()` por `MessageDigest.isEqual()` (timing-safe comparison) |
| `SecurityConfig.java` | Registra `RateLimitFilter` na cadeia de filtros |
| `RateLimitFilter.java` | Arquivo novo — rate limit de 5 req/min por IP em `/api/auth/login` e `/api/auth/register` |

**Decisão:** Commitar como está, sem alteração, em um commit único de segurança.

### 2.2 X-Forwarded-For spoofing no RateLimitFilter

**Problema:** `RateLimitFilter.java:61` lê `X-Forwarded-For` sem validação. Qualquer client pode forjar `X-Forwarded-For: 1.2.3.4` e bypassar o rate limit.

**Decisão:** Remover a leitura de `X-Forwarded-For` do `RateLimitFilter`. O IP real (`request.getRemoteAddr()`) é suficiente porque o Traefik já faz o proxy e o backend nunca é exposto diretamente. O header `X-Real-IP` setado pelo Traefik pode ser lido opcionalmente no futuro com validação de origem, mas não agora.

```java
private String resolveClientIp(HttpServletRequest request) {
    return request.getRemoteAddr(); // remove X-Forwarded-For
}
```

### 2.3 Rate limit no endpoint WhatsApp confirm

**Problema:** `POST /api/v1/users/whatsapp/link/confirm` não está na lista `RATE_LIMITED_PATHS` do `RateLimitFilter`. O código `PIGGY-XXXXXX` tem 1 milhão de combinações — sem rate limit, um atacante com a API Key pode tentar por força bruta.

**Decisão:** Adicionar `/api/v1/users/whatsapp/link/confirm` à lista `RATE_LIMITED_PATHS`. Mesmo limite: 5 req/min por IP.

### 2.4 Recuperação de senha (forgot/reset password)

**Problema:** Usuário que esquece a senha perde acesso permanente. Nenhum endpoint existe para isso.

**Fluxo decidido:**
1. `POST /api/auth/forgot-password` — recebe `{ email }`, **invalida tokens anteriores não-usados do mesmo usuário** (`UPDATE password_reset_tokens SET used = true WHERE user_id = :userId AND used = false`) antes de inserir o novo, gera token seguro (UUID v4), salva na tabela `password_reset_tokens` com expiração de 30 minutos, envia por email. Responde sempre com `200 OK` e mensagem genérica (não revela se o email existe).
2. `POST /api/auth/reset-password` — recebe `{ token, newPassword }`, valida token (existe, não expirado, não usado), **valida `newPassword` pelas mesmas regras do registro (mínimo 8 caracteres, letras e números)** antes de encodar com BCrypt, atualiza senha, marca token como usado.

**Migration V8:** Cria tabela `password_reset_tokens (id UUID PK, user_id UUID FK, token VARCHAR(36) UNIQUE NOT NULL, expires_at TIMESTAMPTZ NOT NULL, used BOOLEAN DEFAULT FALSE)`.

**Transporte do email:** Spring Boot Starter Mail com SMTP. Credenciais via variáveis de ambiente `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`. Email simples com texto plano contendo o link de reset `https://piggyfinance.cloud/reset-password?token=<token>`.

**Rate limit:** O endpoint `POST /api/auth/forgot-password` entra na lista `RATE_LIMITED_PATHS` (5 req/min por IP).

**Página no frontend:** Nova rota `/reset-password` com formulário de nova senha. O token vem via query param `?token=`. Link "Esqueci minha senha" na página `/login`.

### 2.5 Exclusão de conta (LGPD)

**Problema:** LGPD exige direito ao apagamento de dados pessoais. Sem `DELETE /api/v1/users/me`, o app não está em conformidade.

**Decisão:** Implementar `DELETE /api/v1/users/me` que:
- Exige autenticação JWT (usuário só deleta a própria conta)
- **Exige confirmação da senha atual no corpo da requisição `{ currentPassword }`** — valida via BCrypt antes de prosseguir. Impede que um JWT roubado (via XSS de localStorage) seja suficiente para excluir a conta de forma irreversível.
- Apaga em cascata: transações, metas, códigos WhatsApp, tokens de reset de senha
- Apaga o próprio registro de usuário
- As tabelas `transactions`, `goals` e `whatsapp_link_codes` já têm `ON DELETE CASCADE` para `user_id`. A tabela `password_reset_tokens` (nova) também terá.

**Frontend:** Botão "Excluir conta" na página de Profile, dentro de um AlertDialog que **inclui campo de senha** ("Digite sua senha para confirmar"). O AlertDialog coleta `currentPassword` e só habilita o botão de confirmação quando o campo tem conteúdo. Após exclusão bem-sucedida, chama `clearAuthToken()` e redireciona para `/welcome`.

### 2.6 Validação cruzada categoria × tipo

**Problema:** A API aceita `INCOME` com categoria `FOOD` ou `EXPENSE` com categoria `SALARY` sem erro.

**Decisão:** Adicionar validação no `TransactionServiceImpl.validate()`:

```java
private static final Set<CategoryType> EXPENSE_CATEGORIES = Set.of(
    FOOD, TRANSPORT, RENT, HEALTH, EDUCATION, LEISURE, SUBSCRIPTIONS, TRAVEL, OTHER
);
private static final Set<CategoryType> INCOME_CATEGORIES = Set.of(
    SALARY, FREELANCE, INVESTMENT, GIFT
);

// Na validação:
if (category != null) {
    boolean isExpense = type == TransactionType.EXPENSE;
    Set<CategoryType> allowed = isExpense ? EXPENSE_CATEGORIES : INCOME_CATEGORIES;
    if (!allowed.contains(category)) {
        throw new BusinessException("Category " + category + " is not valid for " + type + " transactions");
    }
}
```

---

## 3. Frontend — Problemas e Decisões

### 3.1 URLs hardcoded no código-fonte

**Problema:** `api.ts:1-2` e `goalsService.ts:1` hardcodam `https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/...`. URL interna do EasyPanel exposta no bundle JavaScript público.

**Decisão:** Extrair para variável de ambiente Vite:

```ts
// api.ts
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';
const AUTH_URL = `${API_BASE}/api/auth`;
const BASE_URL = `${API_BASE}/api/v1`;
```

Criar `.env.example`:
```
VITE_API_BASE_URL=https://piggyfinance.cloud
```

`.env.local` (não commitado) para desenvolvimento local.

**Nota:** O `nginx.conf` já tem um proxy `/api/ → piggy-repo_piggy:8080/` para uso em produção via Docker Compose. Com `VITE_API_BASE_URL=` (vazio ou removido), as chamadas vão para o próprio host via nginx. Essa é a configuração correta para produção.

### 3.2 Sem interceptor de 401 — sem logout automático

**Problema:** JWT expira em 1h. Após expirar, todas as chamadas retornam 401 mas o usuário fica preso com erros, sem redirecionamento para login.

**Decisão:** Criar uma função wrapper `apiFetch` em `api.ts` que detecta respostas 401 e executa `clearAuthToken()` + dispara o evento `piggy-auth-changed` (que já existe e já aciona o `clearState` no `FinanceContext`). O `ProtectedRoute` em `App.tsx` já faz redirect para `/welcome` quando não há token, então o fluxo de logout fica completo automaticamente.

```ts
async function apiFetch(input: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(input, init);
  if (res.status === 401) {
    clearAuthToken(); // já dispara 'piggy-auth-changed' internamente
  }
  return res;
}
```

**Nota de implementação:** `clearAuthToken()` já chama `window.dispatchEvent(new CustomEvent('piggy-auth-changed'))` em `api.ts:13`. Não é necessário disparar o evento separadamente no `apiFetch`.

**Escopo de aplicação:** `apiFetch` é usado **apenas nas funções autenticadas** — `createTransaction`, `listTransactions`, `getTransactionSummary`, `getCurrentUser`, `generateWhatsAppLinkCode`, `deleteTransaction` e todas as funções em `goalsService.ts`. **`loginUser` e `registerUser` continuam usando `fetch` diretamente** — elas intencionalmente recebem 401 em credenciais erradas e não devem disparar logout.

### 3.3 "Membro desde Fev 2026" hardcoded

**Problema:** `Profile.tsx:53` tem texto literal de data. O backend já retorna `createdAt` em `UserMeResponse`.

**Decisão:**
- Adicionar `createdAt: string` ao tipo `UserMeResponse` em `api.ts`
- `Profile.tsx` lê `user.createdAt` e formata com `date-fns/format`: `"MMM yyyy"` em pt-BR

### 3.4 Remoção do Supabase

**Problema:** `@supabase/supabase-js` (~500KB) instalado, nunca usado. Arquivo `integrations/supabase/client.ts` nunca importado.

**Decisão:**
- `npm remove @supabase/supabase-js`
- Deletar `src/integrations/supabase/client.ts` e `src/integrations/supabase/types.ts`
- Deletar pasta `src/integrations/` se ficar vazia

### 3.5 QueryClient retry para erros 4xx

**Problema:** React Query retenta 3 vezes por padrão. Um 401 ou 422 gera 3 chamadas extras, consumindo rate limit e confundindo o usuário.

**Decisão:** Configurar `QueryClient` em `App.tsx` para não retentar em erros HTTP:

```ts
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
});
```

### 3.6 Página de recuperação de senha

**Problema:** Sem fluxo de forgot/reset no frontend.

**Decisão:**
- Adicionar link "Esqueci minha senha" em `Login.tsx` abaixo do botão de submit
- Nova página `ForgotPassword.tsx` em `/forgot-password`: formulário com campo email, chama `POST /api/auth/forgot-password`, mostra mensagem de confirmação genérica
- Nova página `ResetPassword.tsx` em `/reset-password`: lê `?token=` da URL, formulário com campos "nova senha" e "confirmar senha" com validação de match, chama `POST /api/auth/reset-password`
- Adicionar as duas rotas em `App.tsx` (sem `ProtectedRoute`)

---

## 4. O que está fora do escopo (decidido não fazer agora)

| Item | Motivo |
|---|---|
| Campo `date` no AddTransaction | Requer migration + mudança no modelo de domínio do backend. Issue separada. |
| Refresh token / revogação de JWT | Aumenta complexidade de infraestrutura. JWT de 1h é aceitável com logout automático em 401. |
| Testes de integração | Escopo B. |
| Swagger/OpenAPI | Melhoria, não blocker. |
| `TransactionRepository.findByUserEmail()` (dead code) | Limpeza, não blocker. |

---

## 5. Migration de banco necessária

**V8** — `password_reset_tokens`:
```sql
CREATE TABLE password_reset_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      VARCHAR(36) UNIQUE NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_tokens_token ON password_reset_tokens(token);
CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
```

---

## 6. Novas variáveis de ambiente necessárias

**Backend:**
```
MAIL_HOST=smtp.example.com
MAIL_PORT=587
MAIL_USERNAME=noreply@piggyfinance.cloud
MAIL_PASSWORD=<secret>
MAIL_FROM=noreply@piggyfinance.cloud
APP_BASE_URL=https://piggyfinance.cloud
```

**Frontend:**
```
VITE_API_BASE_URL=  # vazio em prod (usa nginx proxy), ou URL completa em dev
```

---

## 7. Ordem de implementação recomendada

1. **Backend primeiro** — commits de segurança pendentes, X-Forwarded-For fix, rate limit WhatsApp confirm, validação categoria×tipo
2. **Backend** — migration V8 + modelo + service + controller para forgot/reset password
3. **Backend** — DELETE `/api/v1/users/me`
4. **Frontend** — env var + apiFetch interceptor (base que tudo depende)
5. **Frontend** — remoção Supabase, QueryClient retry, createdAt no Profile
6. **Frontend** — páginas ForgotPassword + ResetPassword + link no Login
7. **Frontend** — botão de exclusão de conta no Profile
