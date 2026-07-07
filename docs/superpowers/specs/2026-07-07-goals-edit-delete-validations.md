# Spec: Goals — Edit/Delete Concluídas + Validações

**Data:** 2026-07-07  
**Status:** Aprovado pelo usuário

---

## Contexto

Metas concluídas (`currentAmount >= targetAmount`) não exibem menu de ações no frontend, tornando impossível editá-las ou excluí-las pela UI. Adicionalmente, o backend aceita silenciosamente valores inválidos (aporte maior que o restante, valor alvo abaixo do já investido), causando comportamento inesperado sem feedback ao usuário.

---

## Objetivo

1. Permitir editar e excluir metas concluídas pelo app.
2. Rejeitar explicitamente entradas inválidas tanto no frontend (feedback imediato) quanto no backend (defesa em profundidade).

---

## Escopo

### Backend — `GoalServiceImpl`

**`create`**
- `targetAmount <= 0` e `amount` nulo já cobertos por `@Positive`/`@NotNull` no DTO → HTTP 400 (Bean Validation).
- Se `currentAmount < 0` → `BusinessException("O valor inicial não pode ser negativo")` → HTTP 422.
- Se `currentAmount > targetAmount` → `BusinessException("O valor inicial não pode ser maior que o valor alvo")` → HTTP 422.
- Remove o `.min(targetAmount)` silencioso atual.

**`update`**
- `targetAmount <= 0` coberto por validação no DTO (`@Positive`) → HTTP 400.
- Se novo `targetAmount < currentAmount` → `BusinessException("O valor alvo não pode ser menor que o já investido (R$ {currentAmount})")` → HTTP 422.

**`addProgress`**
- `amount <= 0` coberto por `@Positive` no DTO → HTTP 400 (Bean Validation, não requer guard no service).
- Se `currentAmount + amount > targetAmount` → `BusinessException("O valor excede o restante da meta (R$ {restante})")` → HTTP 422.
- Remove o `.min(targetAmount)` silencioso atual.

Todas as `BusinessException` são mapeadas para HTTP 422 pelo `GlobalExceptionHandler` existente.

### Frontend — `Goals.tsx`

**Metas concluídas — menu de ações**
- Adicionar `DropdownMenu` com opções Editar e Apagar ao card de metas concluídas, igual ao card de in-progress.
- Botão "Investir nesta meta" **não é exibido** em metas concluídas (restante = 0).
- Layout: `[nome] [⋮]` na linha superior; badge "Concluída" como subtítulo.

**Validação no formulário de edição**
- Em `validate()`: se `parseFloat(targetAmount) < parseFloat(currentAmount)` → erro `"O valor alvo não pode ser menor que o já investido (R$ X)"`.
- Bloqueia submissão antes do request.

**Validação no sheet de aporte (Investir)**
- Em `handleInvest`: se `amount > targetAmount - currentAmount` → erro inline `"Valor excede o restante (R$ X disponível)"`.
- Não chama a API; exibe erro diretamente no campo.

**Criação**
- Sem mudança de UI (`currentAmount` não é editável no form, sempre enviado como 0).
- Erros 422 vindos da API de criação são exibidos via `toastError` (comportamento já existente em `handleCreate`).

---

## O que não muda

- Estrutura de endpoints (nenhum endpoint novo).
- Schema do banco (nenhuma migration).
- Fluxo de criação de metas na UI.
- Comportamento quando `currentAmount + amount == targetAmount` (completa normalmente).

---

## Critérios de aceite

- [ ] Metas concluídas exibem menu `⋮` com Editar e Apagar.
- [ ] Metas concluídas **não** exibem o botão "Investir nesta meta".
- [ ] Editar meta concluída mantendo o `targetAmount` original salva com sucesso.
- [ ] Tentar baixar `targetAmount` abaixo do `currentAmount` mostra erro no form e bloqueia o request.
- [ ] Aporte com `amount > restante` mostra erro inline sem chamar a API.
- [ ] `POST /goals` com `currentAmount > targetAmount` retorna 422.
- [ ] `POST /goals` com `targetAmount <= 0` retorna 400 (Bean Validation).
- [ ] `POST /goals` com `currentAmount < 0` retorna 422.
- [ ] `PUT /goals/:id` com `targetAmount < currentAmount` retorna 422.
- [ ] `PATCH /goals/:id/progress` com `amount <= 0` retorna 400 (Bean Validation).
- [ ] `PATCH /goals/:id/progress` com `amount > restante` retorna 422.
