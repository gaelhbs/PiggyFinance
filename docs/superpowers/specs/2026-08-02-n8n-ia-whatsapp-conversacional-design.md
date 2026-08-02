# Fluxo n8n / IA WhatsApp Conversacional — Design Spec

**Date:** 2026-08-02
**Status:** Approved
**Related specs:** `2026-04-24-whatsapp-account-linking-design.md` (vinculação de conta), `2026-06-04-whatsapp-linking-frontend-design.md` (UI de vinculação)

---

## Problem

Hoje o fluxo n8n do WhatsApp só faz uma coisa: interpreta a mensagem do usuário como uma transação (LLM/parsing) e chama `POST /api/v1/transactions/whatsapp`. Qualquer outra pergunta do usuário ("quanto gastei esse mês?", "como tá minha meta?", "qual meu plano?", "errei o valor, corrige") não tem como ser respondida, porque o backend não expõe nenhum dado além da criação de transação para o número de telefone do usuário.

Este spec decompõe o subsistema "Fluxo n8n / IA mais inteligente" (ver memória `project-launch-subsystems`) na sua primeira fatia: dar ao n8n as ferramentas (endpoints) necessárias para que a IA responda perguntas sobre saldo, metas, status do plano, e edite/exclua a última transação lançada via WhatsApp — sem que o backend precise saber nada sobre LLM, prompts ou orquestração de conversa.

---

## Solution Overview

Arquitetura de **tool-calling**: o n8n passa a rodar como um agente de IA com múltiplas ferramentas (mudança do lado do n8n, fora deste repositório). Cada ferramenta mapeia 1:1 para um novo endpoint no backend, protegido por API Key (mesmo `ApiKeyAuthFilter` já usado por `POST /transactions/whatsapp` e `POST /users/whatsapp/link/confirm`), que:

1. Resolve o usuário pelo `phoneNumber` recebido (`userRepository.findByPhoneNumber`) → `404 PHONE_NOT_LINKED` se não houver conta vinculada.
2. Exige plano **PRO** via `entitlementService.requireTier(userId, SubscriptionTier.PRO)` → `402 FEATURE_LOCKED` se não for PRO — mesma gate já aplicada em `createWhatsAppTransaction`, mantendo a IA WhatsApp como benefício exclusivo do plano PRO (consistente com o pricing definido em `project-launch-subsystems`).
3. Executa a lógica específica do endpoint.

O n8n decide, com base na mensagem do usuário, qual ferramenta chamar — a lógica de intenção/conversa fica inteiramente no n8n; o backend continua sendo apenas uma REST API.

---

## API Changes

Todos os endpoints abaixo são novos, autenticados por API Key (`X-Api-Key`), e adicionados a `ApiKeyAuthFilter.API_KEY_PATHS`.

### `GET /api/v1/transactions/whatsapp/summary`

- **Query params:** `phoneNumber` (obrigatório), `startDate`, `endDate` (opcionais, `ISO.DATE`)
- **Response `200`:** `TransactionSummaryResponse { income, expense, balance }` — reaproveita `TransactionService.getSummary`, já usado pelo endpoint JWT equivalente.

### `GET /api/v1/goals/whatsapp`

- **Query params:** `phoneNumber` (obrigatório)
- **Response `200`:** `List<GoalResponse> [{ id, name, targetAmount, currentAmount, iconName, createdAt }]`

### `GET /api/v1/billing/whatsapp/status`

- **Query params:** `phoneNumber` (obrigatório)
- **Response `200`:** novo record `WhatsAppSubscriptionStatusResponse { tier, status, currentPeriodEnd, cancelAtPeriodEnd }`

### `GET /api/v1/transactions/whatsapp/last`

- **Query params:** `phoneNumber` (obrigatório)
- **Response `200`:** `TransactionResponse { id, description, amount, type, source, category, timestamp }` — a última transação com `source = WHATSAPP` para o usuário, ordenada por `timestamp desc`.
- **Response `404`:** `TRANSACTION_NOT_FOUND` (nova `WhatsAppTransactionNotFoundException`) se o usuário nunca lançou nada via WhatsApp.
- **Uso esperado:** a IA chama este endpoint para confirmar com o usuário qual transação será editada/excluída antes de chamar `PATCH`/`DELETE`.

### `PATCH /api/v1/transactions/whatsapp/last`

- **Request body:** reaproveita `CreateWhatsAppTransactionRequest { phoneNumber, description, amount, type, category }` (mesmos campos e validações do endpoint de criação) — substituição completa dos campos, sem PATCH parcial.
- **Response `200`:** `TransactionResponse` atualizado.
- **Response `404`:** `TRANSACTION_NOT_FOUND` se não houver transação do WhatsApp para editar.
- **Validação:** reaproveita o método `validate(amount, type, category)` já usado em `createWhatsAppTransaction`.

### `DELETE /api/v1/transactions/whatsapp/last`

- **Query params:** `phoneNumber` (obrigatório)
- **Response `204`:** sem corpo.
- **Response `404`:** `TRANSACTION_NOT_FOUND` se não houver transação do WhatsApp para excluir.

---

## Common Behavior

Ordem de validação, igual em todos os 6 endpoints:

1. Telefone vinculado a uma conta → senão `404 PHONE_NOT_LINKED` (`ErrorResponse`, exceção já existente `PhoneNotLinkedException`)
2. Usuário com tier PRO efetivo → senão `402 FEATURE_LOCKED` (`FeatureLockedResponse`, exceção já existente `FeatureLockedException`)
3. Lógica específica do endpoint (incluindo o novo `404 TRANSACTION_NOT_FOUND` para os 3 endpoints de "last")

"Última transação do WhatsApp" = a mais recente com `source = WHATSAPP` para aquele usuário, sem limite de tempo — cabe ao LLM no n8n decidir quando é apropriado chamar essas ferramentas de edição/exclusão a partir do contexto da conversa. Não há versionamento/histórico: uma segunda edição sobrescreve a primeira.

---

## Error Handling

| Cenário | `errorCode` | HTTP | Exceção |
|---|---|---|---|
| Telefone não vinculado | `PHONE_NOT_LINKED` | `404` | `PhoneNotLinkedException` (existente) |
| Usuário não é PRO | `FEATURE_LOCKED` | `402` | `FeatureLockedException` (existente) |
| Nenhuma transação do WhatsApp encontrada | `TRANSACTION_NOT_FOUND` | `404` | `WhatsAppTransactionNotFoundException` (nova) |
| Corpo de `PATCH` inválido (amount/type/category) | — | `400` | Validação Jakarta, mesma do `createWhatsAppTransaction` |

---

## Testing

Seguindo o padrão TDD já usado no projeto (`TransactionServiceImplTest`, `BillingServiceImplTest`, etc.):

- **Unit tests de service**, um conjunto por recurso:
  - `TransactionServiceImpl`: summary/last/update/delete por telefone — casos de sucesso, telefone não vinculado, tier bloqueado (FREE/ESSENCIAL), "last" inexistente.
  - `GoalServiceImpl`: listagem por telefone — mesmos casos de erro comuns.
  - `BillingServiceImpl`: status por telefone — mesmos casos de erro comuns.
- **Controller tests (MockMvc)**: cada rota nova exige API Key válida (401/403 sem ela) e retorna os status HTTP corretos para cada cenário de erro.

---

## Out of Scope

- Reconstrução do workflow n8n em si (agente de IA, tools, prompts) — vive fora deste repositório; este spec define apenas o contrato backend↔n8n.
- Histórico/auditoria de edições de transação.
- Editar/excluir transações que não sejam a última do WhatsApp (ex: listar as últimas N e escolher) — considerado e descartado nesta rodada.
- Editar/excluir transações lançadas pelo app via este fluxo (`source = WHATSAPP` apenas).
- Tom de voz / personalidade da IA — responsabilidade do n8n.
- Gating diferenciado por endpoint (ex: liberar "status do plano" para não-PRO) — todos os 6 endpoints usam a mesma regra PRO-only, por decisão explícita e por consistência com `createWhatsAppTransaction`.
