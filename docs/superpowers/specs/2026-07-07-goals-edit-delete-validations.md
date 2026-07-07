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
- Se `currentAmount > targetAmount` → lança `BusinessException("O valor inicial não pode ser maior que o valor alvo")`
- Remove o `.min(targetAmount)` silencioso atual.

**`update`**
- Se novo `targetAmount < currentAmount` → lança `BusinessException("O valor alvo não pode ser menor que o já investido (R$ {currentAmount})")`

**`addProgress`**
- Se `amount <= 0` → lança `BusinessException("O valor deve ser maior que zero")`
- Se `currentAmount + amount > targetAmount` → lança `BusinessException("O valor excede o restante da meta (R$ {restante})")`
- Remove o `.min(targetAmount)` silencioso atual.

Todas as `BusinessException` são mapeadas para HTTP 422 pelo `GlobalExceptionHandler` existente.

### Frontend — `Goals.tsx`

**Metas concluídas — menu de ações**
- Adicionar `DropdownMenu` com opções Editar e Apagar ao card de metas concluídas, igual ao card de in-progress.
- Layout: `[nome] [⋮]` na linha superior; badge "Concluída" fica abaixo do nome como subtítulo.

**Validação no formulário de edição**
- Em `validate()`: se `parseFloat(targetAmount) < parseFloat(currentAmount)` → erro `"O valor alvo não pode ser menor que o já investido (R$ X)"`.
- Bloqueia submissão antes do request.

**Validação no sheet de aporte (Investir)**
- Em `handleInvest`: se `amount > targetAmount - currentAmount` → erro inline `"Valor excede o restante (R$ X disponível)"`.
- Não chama a API; exibe erro diretamente no campo.

**Criação**
- Sem mudança de UI (`currentAmount` não é editável no form).
- Backend cobre o edge case via API direta.

---

## O que não muda

- Estrutura de endpoints (nenhum endpoint novo).
- Schema do banco (nenhuma migration).
- Fluxo de criação de metas.
- Comportamento do `addGoalProgress` quando `currentAmount + amount == targetAmount` (completa normalmente).

---

## Critérios de aceite

- [ ] Metas concluídas exibem menu Editar/Apagar.
- [ ] Editar meta concluída com `targetAmount` original funciona normalmente.
- [ ] Tentar baixar `targetAmount` abaixo do `currentAmount` mostra erro no form.
- [ ] Aporte com valor maior que o restante mostra erro inline sem chamar a API.
- [ ] API retorna 422 com mensagem clara para todos os casos inválidos.
- [ ] API retorna 422 com mensagem clara quando `amount <= 0` no aporte.
