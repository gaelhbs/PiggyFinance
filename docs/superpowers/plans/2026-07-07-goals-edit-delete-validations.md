# Goals — Edit/Delete Concluídas + Validações Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar menu de edição/exclusão em metas concluídas e rejeitar entradas inválidas no backend (422) e frontend (erro inline).

**Architecture:** Backend valida via `BusinessException` no service (422); Bean Validation existente cobre `amount <= 0` e `targetAmount <= 0` (400). Frontend valida antes do request e expõe menu `⋮` em cards concluídos.

**Tech Stack:** Java 21 + Spring Boot 3, JUnit 5 + Mockito + AssertJ, React 18 + TypeScript, TanStack Query.

## Global Constraints

- Mensagens de erro em português.
- Não adicionar endpoints novos — usar os existentes.
- Nenhuma migration de banco.
- `BusinessException` → HTTP 422 (já configurado no `GlobalExceptionHandler`).
- Testes backend usam `@ExtendWith(MockitoExtension.class)` + AssertJ.

---

### Task 1: Backend — validações em `addProgress`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java`

**Interfaces:**
- Consumes: `GoalProgressRequest(BigDecimal amount)`, `Goal.getCurrentAmount()`, `Goal.getTargetAmount()`
- Produces: `addProgress` lança `BusinessException` quando `currentAmount + amount > targetAmount`

- [ ] **Step 1: Atualizar teste existente que esperava clamping**

Em `GoalServiceImplTest.java`, substituir o teste `addProgress_addsAmountAndClampsAtTarget`:

```java
@Test
void addProgress_throwsWhenAmountExceedsRemaining() {
    when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));

    // goal: currentAmount=10000, targetAmount=50000, remaining=40000
    GoalProgressRequest req = new GoalProgressRequest(new BigDecimal("40001"));
    assertThatThrownBy(() -> service.addProgress(goalId, req, userId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("excede o restante");
}

@Test
void addProgress_exactRemainingCompletes() {
    Goal completed = goal.toBuilder().currentAmount(new BigDecimal("50000")).build();
    when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));
    when(goalRepository.save(any())).thenReturn(completed);

    GoalProgressRequest req = new GoalProgressRequest(new BigDecimal("40000"));
    GoalResponse response = service.addProgress(goalId, req, userId);

    assertThat(response.currentAmount()).isEqualByComparingTo("50000");
}
```

- [ ] **Step 2: Rodar testes — confirmar falha**

```bash
./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest" 2>&1 | tail -20
```
Esperado: `addProgress_throwsWhenAmountExceedsRemaining` FAIL.

- [ ] **Step 3: Implementar validação em `addProgress`**

Em `GoalServiceImpl.java`, substituir o método `addProgress`:

```java
@Override
@Transactional
public GoalResponse addProgress(UUID goalId, GoalProgressRequest request, UUID userId) {
    Goal goal = findOwned(goalId, userId);
    BigDecimal remaining = goal.getTargetAmount().subtract(goal.getCurrentAmount());
    if (request.amount().compareTo(remaining) > 0) {
        throw new BusinessException(
            "O valor excede o restante da meta (R$ " +
            remaining.setScale(2, java.math.RoundingMode.HALF_UP)
                     .toPlainString().replace(".", ",") + ")");
    }
    Goal updated = goalRepository.save(goal.toBuilder()
            .currentAmount(goal.getCurrentAmount().add(request.amount()))
            .build());
    return toResponse(updated);
}
```

- [ ] **Step 4: Rodar testes — confirmar verde**

```bash
./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest" 2>&1 | tail -20
```
Esperado: todos PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java \
        src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java
git commit -m "feat: reject addProgress when amount exceeds remaining"
```

---

### Task 2: Backend — validação em `update`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java`

**Interfaces:**
- Consumes: `UpdateGoalRequest(String name, BigDecimal targetAmount, String iconName)`, `Goal.getCurrentAmount()`
- Produces: `update` lança `BusinessException` quando `request.targetAmount() < goal.getCurrentAmount()`

- [ ] **Step 1: Escrever teste com falha**

Adicionar em `GoalServiceImplTest.java`:

```java
@Test
void update_throwsWhenNewTargetBelowCurrentAmount() {
    // goal: currentAmount=10000
    when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));

    UpdateGoalRequest req = new UpdateGoalRequest("Casa", new BigDecimal("9999"), "Home");
    assertThatThrownBy(() -> service.update(goalId, req, userId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("não pode ser menor que o já investido");
}

@Test
void update_allowsTargetEqualToCurrentAmount() {
    // currentAmount=10000, new target=10000 → valid (goal becomes complete)
    Goal updated = goal.toBuilder().targetAmount(new BigDecimal("10000")).build();
    when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));
    when(goalRepository.save(any())).thenReturn(updated);

    UpdateGoalRequest req = new UpdateGoalRequest("Casa", new BigDecimal("10000"), "Home");
    GoalResponse response = service.update(goalId, req, userId);

    assertThat(response.targetAmount()).isEqualByComparingTo("10000");
}
```

- [ ] **Step 2: Rodar testes — confirmar falha**

```bash
./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest" 2>&1 | tail -20
```
Esperado: `update_throwsWhenNewTargetBelowCurrentAmount` FAIL.

- [ ] **Step 3: Implementar validação em `update`**

Em `GoalServiceImpl.java`, substituir o método `update`:

```java
@Override
@Transactional
public GoalResponse update(UUID goalId, UpdateGoalRequest request, UUID userId) {
    Goal goal = findOwned(goalId, userId);
    if (request.targetAmount().compareTo(goal.getCurrentAmount()) < 0) {
        throw new BusinessException(
            "O valor alvo não pode ser menor que o já investido (R$ " +
            goal.getCurrentAmount().setScale(2, java.math.RoundingMode.HALF_UP)
                                   .toPlainString().replace(".", ",") + ")");
    }
    Goal updated = goalRepository.save(goal.toBuilder()
            .name(request.name())
            .targetAmount(request.targetAmount())
            .iconName(request.iconName())
            .build());
    return toResponse(updated);
}
```

- [ ] **Step 4: Rodar testes — confirmar verde**

```bash
./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest" 2>&1 | tail -20
```
Esperado: todos PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java \
        src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java
git commit -m "feat: reject update when targetAmount is below currentAmount"
```

---

### Task 3: Backend — validações em `create`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java`

**Interfaces:**
- Consumes: `CreateGoalRequest(String name, BigDecimal targetAmount, BigDecimal currentAmount, String iconName)`
- Produces: `create` lança `BusinessException` para `currentAmount < 0` ou `currentAmount > targetAmount`

- [ ] **Step 1: Atualizar teste existente + escrever novos**

Em `GoalServiceImplTest.java`, substituir `create_clampsInitialAmountToTarget` e adicionar:

```java
@Test
void create_throwsWhenInitialAmountExceedsTarget() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    CreateGoalRequest req = new CreateGoalRequest("Casa", new BigDecimal("50000"), new BigDecimal("50001"), "Home");
    assertThatThrownBy(() -> service.create(req, userId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("não pode ser maior que o valor alvo");
}

@Test
void create_throwsWhenInitialAmountIsNegative() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    CreateGoalRequest req = new CreateGoalRequest("Casa", new BigDecimal("50000"), new BigDecimal("-1"), "Home");
    assertThatThrownBy(() -> service.create(req, userId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("não pode ser negativo");
}

@Test
void create_allowsInitialAmountEqualToTarget() {
    Goal full = goal.toBuilder().currentAmount(new BigDecimal("50000")).build();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(goalRepository.save(any())).thenReturn(full);

    CreateGoalRequest req = new CreateGoalRequest("Casa", new BigDecimal("50000"), new BigDecimal("50000"), "Home");
    GoalResponse response = service.create(req, userId);

    assertThat(response.currentAmount()).isEqualByComparingTo("50000");
}
```

- [ ] **Step 2: Rodar testes — confirmar falha**

```bash
./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest" 2>&1 | tail -20
```
Esperado: `create_throwsWhenInitialAmountExceedsTarget` e `create_throwsWhenInitialAmountIsNegative` FAIL.

- [ ] **Step 3: Implementar validações em `create`**

Em `GoalServiceImpl.java`, substituir o método `create`:

```java
@Override
@Transactional
public GoalResponse create(CreateGoalRequest request, UUID userId) {
    User user = findUser(userId);
    BigDecimal initial = request.currentAmount() != null ? request.currentAmount() : BigDecimal.ZERO;
    if (initial.compareTo(BigDecimal.ZERO) < 0) {
        throw new BusinessException("O valor inicial não pode ser negativo");
    }
    if (initial.compareTo(request.targetAmount()) > 0) {
        throw new BusinessException("O valor inicial não pode ser maior que o valor alvo");
    }
    Goal goal = goalRepository.save(Goal.builder()
            .user(user)
            .name(request.name())
            .targetAmount(request.targetAmount())
            .currentAmount(initial)
            .iconName(request.iconName())
            .build());
    return toResponse(goal);
}
```

- [ ] **Step 4: Rodar todos os testes do backend**

```bash
./gradlew test 2>&1 | tail -20
```
Esperado: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java \
        src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java
git commit -m "feat: validate create goal initial amount"
```

---

### Task 4: Frontend — menu em metas concluídas + hide Investir + validate()

**Files:**
- Modify: `src/pages/Goals.tsx` (frontend repo: `/Users/gabrielbraga/Documents/Projects/flutter/piggyapp`)

**Interfaces:**
- Consumes: `openEdit(goal)`, `setDeleteId(goal.id)` — já existentes
- Produces: cards concluídos com `DropdownMenu`; botão Investir ausente nesses cards; `validate()` com guard de `targetAmount < currentAmount`

- [ ] **Step 1: Atualizar `validate()` para rejeitar targetAmount < currentAmount**

Em `Goals.tsx`, substituir a função `validate`:

```tsx
const validate = (f: GoalFormState) => {
  const e: { name?: string; target?: string } = {};
  if (!f.name.trim()) e.name = 'Informe um nome';
  if (!f.targetAmount || parseFloat(f.targetAmount) <= 0) e.target = 'Informe o valor alvo';
  if (f.currentAmount && parseFloat(f.targetAmount) < parseFloat(f.currentAmount)) {
    e.target = `O valor alvo não pode ser menor que o já investido (${fmt(parseFloat(f.currentAmount))})`;
  }
  return e;
};
```

- [ ] **Step 2: Substituir o bloco `completed.map()` para adicionar menu e remover Investir**

Em `Goals.tsx`, substituir o bloco `{completed.map(goal => (` inteiro:

```tsx
{completed.map(goal => (
  <div key={goal.id} className="bg-card rounded-2xl p-4 shadow-card space-y-3 opacity-80">
    <div className="flex items-start gap-3">
      <div className="w-10 h-10 rounded-xl bg-income-soft flex items-center justify-center shrink-0">
        <GoalIcon name={goal.iconName} className="text-income" />
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between">
          <h3 className="font-semibold text-sm truncate">{goal.name}</h3>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button className="p-1 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-colors shrink-0">
                <MoreVertical size={16} />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => openEdit(goal)} className="gap-2 cursor-pointer">
                <Pencil size={14} /> Editar
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => setDeleteId(goal.id)} className="gap-2 cursor-pointer text-destructive focus:text-destructive">
                <Trash2 size={14} /> Apagar
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
        <div className="flex items-center gap-2 mt-0.5">
          <span className="text-xs bg-income-soft text-income px-2 py-0.5 rounded-full font-medium flex items-center gap-1">
            <Check size={10} /> Concluída
          </span>
          <p className="text-xs text-muted-foreground">{fmt(goal.targetAmount)}</p>
        </div>
      </div>
    </div>
    <Progress value={100} className="h-2" />
  </div>
))}
```

- [ ] **Step 3: Verificar TypeScript**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp && npx tsc --noEmit 2>&1
```
Esperado: sem erros.

- [ ] **Step 4: Commit**

```bash
git -C /Users/gabrielbraga/Documents/Projects/flutter/piggyapp \
  add src/pages/Goals.tsx && \
git -C /Users/gabrielbraga/Documents/Projects/flutter/piggyapp \
  commit -m "feat: add edit/delete menu to completed goals and validate targetAmount"
```

---

### Task 5: Frontend — validação inline no sheet de aporte

**Files:**
- Modify: `src/pages/Goals.tsx` (frontend repo: `/Users/gabrielbraga/Documents/Projects/flutter/piggyapp`)

**Interfaces:**
- Consumes: `investGoal.targetAmount`, `investGoal.currentAmount`, `investAmount` (state local)
- Produces: `handleInvest` rejeita antes do request quando `amount > remaining`; mensagem exibe o restante disponível

- [ ] **Step 1: Atualizar `handleInvest` com validação de restante**

Em `Goals.tsx`, substituir o método `handleInvest`:

```tsx
const handleInvest = async () => {
  if (!investGoal) return;
  const v = parseFloat(investAmount);
  if (!investAmount || v <= 0) { setInvestError('Informe um valor válido'); return; }
  const remaining = investGoal.targetAmount - investGoal.currentAmount;
  if (v > remaining) {
    setInvestError(`Valor excede o restante (${fmt(remaining)} disponível)`);
    return;
  }
  try {
    await addProgress.mutateAsync({ id: investGoal.id, amount: v });
    setInvestGoal(null);
    setInvestAmount('');
    setInvestError('');
  } catch (err) { toastError(getApiErrorMessage(err)); }
};
```

- [ ] **Step 2: Verificar TypeScript**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp && npx tsc --noEmit 2>&1
```
Esperado: sem erros.

- [ ] **Step 3: Rodar todos os testes do backend (smoke check final)**

```bash
cd /Users/gabrielbraga/Documents/Projects/java/PiggyFinance && ./gradlew test 2>&1 | tail -10
```
Esperado: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit frontend + push ambos os repos**

```bash
git -C /Users/gabrielbraga/Documents/Projects/flutter/piggyapp \
  add src/pages/Goals.tsx && \
git -C /Users/gabrielbraga/Documents/Projects/flutter/piggyapp \
  commit -m "feat: validate invest amount against remaining in goal sheet"

git -C /Users/gabrielbraga/Documents/Projects/flutter/piggyapp push origin main
git -C /Users/gabrielbraga/Documents/Projects/java/PiggyFinance push origin master
```
