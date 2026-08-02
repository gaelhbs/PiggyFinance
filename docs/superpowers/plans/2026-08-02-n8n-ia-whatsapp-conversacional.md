# n8n IA WhatsApp Conversacional — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the n8n WhatsApp AI agent six new API-key-protected, phone-scoped endpoints so it can answer balance/goals/subscription questions and edit or delete the user's most recent WhatsApp transaction, without the Java backend knowing anything about LLMs or conversation state.

**Architecture:** Each new endpoint follows the exact pattern already used by `TransactionServiceImpl.createWhatsAppTransaction`: resolve the `User` by `phoneNumber` (→ `404 PHONE_NOT_LINKED` if none), require `SubscriptionTier.PRO` via `EntitlementService.requireTier` (→ `402 FEATURE_LOCKED` if not), then perform the resource-specific logic. Endpoints live on the existing `TransactionController`, `GoalController`, and `BillingController` under `/whatsapp` sub-paths and are added to `ApiKeyAuthFilter.API_KEY_PATHS`.

**Tech Stack:** Spring Boot, Spring Data JPA, Spring Security, Lombok, MapStruct, JUnit 5 + Mockito + AssertJ (`./gradlew test`).

## Global Constraints

- All new endpoints use `X-Api-Key` auth via the existing `ApiKeyAuthFilter` — never JWT.
- All new endpoints require `SubscriptionTier.PRO` via `EntitlementService.requireTier(userId, SubscriptionTier.PRO)` — no endpoint is usable by FREE/ESSENCIAL, matching `createWhatsAppTransaction`.
- Error responses reuse existing types: `ErrorResponse` (`PHONE_NOT_LINKED` → 404, `TRANSACTION_NOT_FOUND` → 404) and `FeatureLockedResponse` (`FEATURE_LOCKED` → 402). No new error response shapes.
- "Última transação do WhatsApp" = most recent `Transaction` with `source = TransactionSourceEnum.WHATSAPP` for that user, ordered by `timestamp desc`. No time window.
- Spec of record: `docs/superpowers/specs/2026-08-02-n8n-ia-whatsapp-conversacional-design.md`.

---

### Task 1: Extract `resolveWhatsAppUser` helper in `TransactionServiceImpl`

Every new endpoint needs "find user by phone → require PRO". `createWhatsAppTransaction` already does this inline; extract it into a reusable private helper before adding new callers, and lock in the previously-untested "phone not linked" path with a test.

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java`

**Interfaces:**
- Produces: `private User resolveWhatsAppUser(String phoneNumber)` on `TransactionServiceImpl` — throws `PhoneNotLinkedException` if no user has that phone, throws `FeatureLockedException` if the resolved user isn't PRO, otherwise returns the `User`. Tasks 3–5 call this directly (it's private to this class, so `GoalServiceImpl`/`BillingServiceImpl` will duplicate the same two lines inline — that's fine, it's 2 lines and those classes don't share a base class).

- [ ] **Step 1: Write the failing test for the previously-uncovered phone-not-linked path**

Add to `TransactionServiceImplTest.java` (near `createWhatsAppTransaction_nonPro_throwsFeatureLocked`):

```java
@Test
void createWhatsAppTransaction_phoneNotLinked_throwsPhoneNotLinkedException() {
    var phone = "+5575900000001";
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());

    var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
            phone, "Café", new BigDecimal("10"), TransactionType.EXPENSE, CategoryType.FOOD);

    assertThatThrownBy(() -> service.createWhatsAppTransaction(req))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.PhoneNotLinkedException.class);
    verify(transactionRepository, never()).save(any());
}
```

- [ ] **Step 2: Run the test to verify it fails or passes for the wrong reason**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: this specific test PASSES already (the inline logic in `createWhatsAppTransaction` already throws `PhoneNotLinkedException`) — that's fine, it's a regression guard for the refactor in Step 3, not a red/green test. Confirm all existing tests in the class still pass before refactoring.

- [ ] **Step 3: Extract the helper and use it in `createWhatsAppTransaction`**

In `TransactionServiceImpl.java`, replace lines 87–91:

```java
        User user = userRepository.findByPhoneNumber(request.phoneNumber())
                .orElseThrow(() -> new PhoneNotLinkedException(
                        "No account linked to this phone number. Please link your WhatsApp in the app."));

        entitlementService.requireTier(user.getId(), SubscriptionTier.PRO);
```

with:

```java
        User user = resolveWhatsAppUser(request.phoneNumber());
```

Add the new private helper at the bottom of the class, next to `findUserById`:

```java
    private User resolveWhatsAppUser(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new PhoneNotLinkedException(
                        "No account linked to this phone number. Please link your WhatsApp in the app."));
        entitlementService.requireTier(user.getId(), SubscriptionTier.PRO);
        return user;
    }
```

- [ ] **Step 4: Run the full test class to confirm no regression**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: PASS, all tests including `createWhatsAppTransaction_nonPro_throwsFeatureLocked` and the new phone-not-linked test.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java
git commit -m "refactor: extract resolveWhatsAppUser helper in TransactionServiceImpl"
```

---

### Task 2: `GET /transactions/whatsapp/summary`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/TransactionService.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/TransactionController.java`
- Modify: `src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java`

**Interfaces:**
- Consumes: `resolveWhatsAppUser(String)` from Task 1; existing `getSummary(UUID, LocalDate, LocalDate)` on the same class.
- Produces: `TransactionSummaryResponse getSummaryByPhone(String phoneNumber, LocalDate startDate, LocalDate endDate)` on `TransactionService`/`TransactionServiceImpl` — later tasks don't depend on this, but keep the signature exact for the controller wiring below.

- [ ] **Step 1: Write the failing tests**

Add to `TransactionServiceImplTest.java`:

```java
@Test
void getSummaryByPhone_success_delegatesToGetSummary() {
    var phone = "+5575900000002";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w2@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    when(transactionRepository.getSummary(eq(u.getId()), any(), any())).thenReturn(java.util.List.of());

    var result = service.getSummaryByPhone(phone, null, null);

    assertThat(result.balance()).isEqualTo(BigDecimal.ZERO);
}

@Test
void getSummaryByPhone_phoneNotLinked_throws() {
    var phone = "+5575900000003";
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getSummaryByPhone(phone, null, null))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.PhoneNotLinkedException.class);
}

@Test
void getSummaryByPhone_nonPro_throwsFeatureLocked() {
    var phone = "+5575900000004";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w3@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    org.mockito.Mockito.doThrow(new com.piggy.piggyfinance.exceptions.FeatureLockedException(
                    "This feature requires the PRO plan", SubscriptionTier.PRO))
            .when(entitlementService).requireTier(u.getId(), SubscriptionTier.PRO);

    assertThatThrownBy(() -> service.getSummaryByPhone(phone, null, null))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: FAIL — `getSummaryByPhone` does not exist yet (compile error).

- [ ] **Step 3: Add the method to the interface**

In `TransactionService.java`, add:

```java
    TransactionSummaryResponse getSummaryByPhone(String phoneNumber, LocalDate startDate, LocalDate endDate);
```

- [ ] **Step 4: Implement in `TransactionServiceImpl`**

Add next to `getSummary`:

```java
    @Override
    public TransactionSummaryResponse getSummaryByPhone(String phoneNumber, LocalDate startDate, LocalDate endDate) {
        User user = resolveWhatsAppUser(phoneNumber);
        return getSummary(user.getId(), startDate, endDate);
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: PASS

- [ ] **Step 6: Wire the controller endpoint**

In `TransactionController.java`, add next to the existing `summary` method:

```java
    @GetMapping("/whatsapp/summary")
    @ResponseStatus(HttpStatus.OK)
    public TransactionSummaryResponse whatsappSummary(
            @RequestParam String phoneNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return transactionService.getSummaryByPhone(phoneNumber, startDate, endDate);
    }
```

- [ ] **Step 7: Add the route to `ApiKeyAuthFilter`**

In `ApiKeyAuthFilter.java`, change:

```java
    private static final List<String> API_KEY_PATHS = List.of(
            "/api/v1/transactions/whatsapp",
            "/api/v1/users/whatsapp/link/confirm"
    );
```

to:

```java
    private static final List<String> API_KEY_PATHS = List.of(
            "/api/v1/transactions/whatsapp",
            "/api/v1/users/whatsapp/link/confirm",
            "/api/v1/transactions/whatsapp/summary"
    );
```

- [ ] **Step 8: Build to confirm the controller compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/TransactionService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/TransactionController.java \
        src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java \
        src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java
git commit -m "feat: add GET /transactions/whatsapp/summary for n8n AI agent"
```

---

### Task 3: `WhatsAppTransactionNotFoundException` + `GET /transactions/whatsapp/last`

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/exceptions/WhatsAppTransactionNotFoundException.java`
- Modify: `src/main/java/com/piggy/piggyfinance/exceptions/handler/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/TransactionService.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/TransactionController.java`
- Modify: `src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java`

**Interfaces:**
- Consumes: `resolveWhatsAppUser(String)` from Task 1.
- Produces: `Optional<Transaction> findFirstByUserIdAndSourceOrderByTimestampDesc(UUID userId, TransactionSourceEnum source)` on `TransactionRepository`; `TransactionResponse getLastWhatsAppTransaction(String phoneNumber)` on `TransactionService`/`TransactionServiceImpl` — Tasks 4 and 5 reuse this repository method and the "not found" exception.

- [ ] **Step 1: Write the failing tests**

Add to `TransactionServiceImplTest.java`:

```java
@Test
void getLastWhatsAppTransaction_success_returnsMostRecent() {
    var phone = "+5575900000005";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w4@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    Transaction tx = mock(Transaction.class);
    TransactionResponse resp = mock(TransactionResponse.class);
    when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
            .thenReturn(Optional.of(tx));
    when(transactionMapper.toResponse(tx)).thenReturn(resp);

    assertThat(service.getLastWhatsAppTransaction(phone)).isEqualTo(resp);
}

@Test
void getLastWhatsAppTransaction_noneFound_throwsWhatsAppTransactionNotFound() {
    var phone = "+5575900000006";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w5@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
            .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getLastWhatsAppTransaction(phone))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.WhatsAppTransactionNotFoundException.class);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: FAIL — compile errors (`WhatsAppTransactionNotFoundException`, repository method, and service method don't exist yet).

- [ ] **Step 3: Create the exception**

```java
package com.piggy.piggyfinance.exceptions;

public class WhatsAppTransactionNotFoundException extends RuntimeException {
    public WhatsAppTransactionNotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Wire it into `GlobalExceptionHandler`**

Add next to `handlePhoneNotLinked`:

```java
    @ExceptionHandler(WhatsAppTransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWhatsAppTransactionNotFound(WhatsAppTransactionNotFoundException ex) {
        log.warn("WhatsApp transaction not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("TRANSACTION_NOT_FOUND", ex.getMessage()));
    }
```

- [ ] **Step 5: Add the repository method**

In `TransactionRepository.java`, add (uses the same nested-property traversal Spring Data already relies on for `findByUserEmail`):

```java
    Optional<Transaction> findFirstByUserIdAndSourceOrderByTimestampDesc(UUID userId, TransactionSourceEnum source);
```

Add the two needed imports at the top of the file:

```java
import com.piggy.piggyfinance.enums.TransactionSourceEnum;
import java.util.Optional;
```

- [ ] **Step 6: Add the method to the `TransactionService` interface**

```java
    TransactionResponse getLastWhatsAppTransaction(String phoneNumber);
```

- [ ] **Step 7: Implement in `TransactionServiceImpl`**

```java
    @Override
    public TransactionResponse getLastWhatsAppTransaction(String phoneNumber) {
        User user = resolveWhatsAppUser(phoneNumber);
        Transaction last = findLastWhatsAppTransaction(user.getId());
        return transactionMapper.toResponse(last);
    }

    private Transaction findLastWhatsAppTransaction(UUID userId) {
        return transactionRepository
                .findFirstByUserIdAndSourceOrderByTimestampDesc(userId, TransactionSourceEnum.WHATSAPP)
                .orElseThrow(() -> new WhatsAppTransactionNotFoundException(
                        "No WhatsApp transaction found for this account."));
    }
```

(the private `findLastWhatsAppTransaction` helper is reused by Tasks 4 and 5 — add the import `com.piggy.piggyfinance.exceptions.WhatsAppTransactionNotFoundException` at the top of the file.)

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: PASS

- [ ] **Step 9: Wire the controller endpoint**

In `TransactionController.java`, add:

```java
    @GetMapping("/whatsapp/last")
    @ResponseStatus(HttpStatus.OK)
    public TransactionResponse getLastWhatsAppTransaction(@RequestParam String phoneNumber) {
        return transactionService.getLastWhatsAppTransaction(phoneNumber);
    }
```

- [ ] **Step 10: Add the route to `ApiKeyAuthFilter`**

Add `"/api/v1/transactions/whatsapp/last"` to `API_KEY_PATHS` (this single path also covers Tasks 4 and 5's `PATCH`/`DELETE` on the same URI — the filter matches by URI only, not HTTP method).

- [ ] **Step 11: Build to confirm everything compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/exceptions/WhatsAppTransactionNotFoundException.java \
        src/main/java/com/piggy/piggyfinance/exceptions/handler/GlobalExceptionHandler.java \
        src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java \
        src/main/java/com/piggy/piggyfinance/service/TransactionService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/TransactionController.java \
        src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java \
        src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java
git commit -m "feat: add GET /transactions/whatsapp/last for n8n AI agent"
```

---

### Task 4: `PATCH /transactions/whatsapp/last`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/TransactionService.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/TransactionController.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java`

**Interfaces:**
- Consumes: `resolveWhatsAppUser(String)` and `findLastWhatsAppTransaction(UUID)` from Tasks 1 and 3; `validate(BigDecimal, TransactionType, CategoryType)` (existing private method).
- Produces: `TransactionResponse updateLastWhatsAppTransaction(CreateWhatsAppTransactionRequest request)` — request body reuses the existing `CreateWhatsAppTransactionRequest` record (same fields, no new DTO).

- [ ] **Step 1: Write the failing tests**

Add to `TransactionServiceImplTest.java`:

```java
@Test
void updateLastWhatsAppTransaction_success_replacesFieldsAndSaves() {
    var phone = "+5575900000007";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w6@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    Transaction existing = Transaction.builder()
            .id(UUID.randomUUID()).description("Old").amount(new BigDecimal("10"))
            .type(TransactionType.EXPENSE).source(TransactionSourceEnum.WHATSAPP)
            .category(CategoryType.FOOD).timestamp(LocalDateTime.now()).user(u).build();
    when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
            .thenReturn(Optional.of(existing));
    Transaction saved = mock(Transaction.class);
    TransactionResponse resp = mock(TransactionResponse.class);
    when(transactionRepository.save(any())).thenReturn(saved);
    when(transactionMapper.toResponse(saved)).thenReturn(resp);

    var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
            phone, "Almoço", new BigDecimal("50"), TransactionType.EXPENSE, CategoryType.FOOD);

    assertThat(service.updateLastWhatsAppTransaction(req)).isEqualTo(resp);
    verify(transactionRepository).save(any());
}

@Test
void updateLastWhatsAppTransaction_noneFound_throwsWhatsAppTransactionNotFound() {
    var phone = "+5575900000008";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w7@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
            .thenReturn(Optional.empty());

    var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
            phone, "Almoço", new BigDecimal("50"), TransactionType.EXPENSE, CategoryType.FOOD);

    assertThatThrownBy(() -> service.updateLastWhatsAppTransaction(req))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.WhatsAppTransactionNotFoundException.class);
    verify(transactionRepository, never()).save(any());
}

@Test
void updateLastWhatsAppTransaction_invalidAmount_throwsBusinessException() {
    var req = new com.piggy.piggyfinance.model.requests.CreateWhatsAppTransactionRequest(
            "+5575900000009", "Almoço", BigDecimal.ZERO, TransactionType.EXPENSE, CategoryType.FOOD);

    assertThatThrownBy(() -> service.updateLastWhatsAppTransaction(req))
            .isInstanceOf(BusinessException.class);
    verify(userRepository, never()).findByPhoneNumber(any());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: FAIL — `updateLastWhatsAppTransaction` doesn't exist.

- [ ] **Step 3: Add the method to the `TransactionService` interface**

```java
    TransactionResponse updateLastWhatsAppTransaction(CreateWhatsAppTransactionRequest request);
```

- [ ] **Step 4: Implement in `TransactionServiceImpl`**

```java
    @Override
    @Transactional
    public TransactionResponse updateLastWhatsAppTransaction(CreateWhatsAppTransactionRequest request) {
        validate(request.amount(), request.type(), request.category());

        User user = resolveWhatsAppUser(request.phoneNumber());
        Transaction existing = findLastWhatsAppTransaction(user.getId());

        Transaction updated = transactionRepository.save(existing.toBuilder()
                .description(request.description())
                .amount(request.amount())
                .type(request.type())
                .category(request.category())
                .build());

        log.info("WhatsApp transaction updated: {}", updated.getId());
        return transactionMapper.toResponse(updated);
    }
```

Note: `validate(...)` runs before `resolveWhatsAppUser(...)` — matches the existing order in `createTransaction`/`createWhatsAppTransaction` (validate first, so a bad amount never even looks up the user), which is why the last test asserts `userRepository` is never called.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: PASS

- [ ] **Step 6: Wire the controller endpoint**

In `TransactionController.java`, add:

```java
    @PatchMapping("/whatsapp/last")
    @ResponseStatus(HttpStatus.OK)
    public TransactionResponse updateLastWhatsAppTransaction(
            @RequestBody @Valid CreateWhatsAppTransactionRequest request) {
        return transactionService.updateLastWhatsAppTransaction(request);
    }
```

(No `ApiKeyAuthFilter` change needed — `/api/v1/transactions/whatsapp/last` was already added in Task 3 and the filter matches by URI, not method.)

- [ ] **Step 7: Build to confirm everything compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/TransactionService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/TransactionController.java \
        src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java
git commit -m "feat: add PATCH /transactions/whatsapp/last for n8n AI agent"
```

---

### Task 5: `DELETE /transactions/whatsapp/last`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/TransactionService.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/TransactionController.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java`

**Interfaces:**
- Consumes: `resolveWhatsAppUser(String)` and `findLastWhatsAppTransaction(UUID)` from Tasks 1 and 3.
- Produces: `void deleteLastWhatsAppTransaction(String phoneNumber)`.

- [ ] **Step 1: Write the failing tests**

Add to `TransactionServiceImplTest.java`:

```java
@Test
void deleteLastWhatsAppTransaction_success_deletes() {
    var phone = "+5575900000010";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w8@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    Transaction existing = Transaction.builder()
            .id(UUID.randomUUID()).description("Old").amount(new BigDecimal("10"))
            .type(TransactionType.EXPENSE).source(TransactionSourceEnum.WHATSAPP)
            .category(CategoryType.FOOD).timestamp(LocalDateTime.now()).user(u).build();
    when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
            .thenReturn(Optional.of(existing));

    service.deleteLastWhatsAppTransaction(phone);

    verify(transactionRepository).delete(existing);
}

@Test
void deleteLastWhatsAppTransaction_noneFound_throwsWhatsAppTransactionNotFound() {
    var phone = "+5575900000011";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("w9@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    when(transactionRepository.findFirstByUserIdAndSourceOrderByTimestampDesc(u.getId(), TransactionSourceEnum.WHATSAPP))
            .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteLastWhatsAppTransaction(phone))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.WhatsAppTransactionNotFoundException.class);
    verify(transactionRepository, never()).delete(any(Transaction.class));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: FAIL — `deleteLastWhatsAppTransaction` doesn't exist.

- [ ] **Step 3: Add the method to the `TransactionService` interface**

```java
    void deleteLastWhatsAppTransaction(String phoneNumber);
```

- [ ] **Step 4: Implement in `TransactionServiceImpl`**

```java
    @Override
    @Transactional
    public void deleteLastWhatsAppTransaction(String phoneNumber) {
        User user = resolveWhatsAppUser(phoneNumber);
        Transaction last = findLastWhatsAppTransaction(user.getId());
        transactionRepository.delete(last);
        log.info("WhatsApp transaction deleted: {}", last.getId());
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.TransactionServiceImplTest"`
Expected: PASS

- [ ] **Step 6: Wire the controller endpoint**

In `TransactionController.java`, add:

```java
    @DeleteMapping("/whatsapp/last")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLastWhatsAppTransaction(@RequestParam String phoneNumber) {
        transactionService.deleteLastWhatsAppTransaction(phoneNumber);
    }
```

- [ ] **Step 7: Build to confirm everything compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/TransactionService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/TransactionController.java \
        src/test/java/com/piggy/piggyfinance/service/TransactionServiceImplTest.java
git commit -m "feat: add DELETE /transactions/whatsapp/last for n8n AI agent"
```

---

### Task 6: `GET /goals/whatsapp`

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/GoalService.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/GoalController.java`
- Modify: `src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java`

**Interfaces:**
- Consumes: existing `list(UUID userId)` on `GoalServiceImpl`.
- Produces: `List<GoalResponse> listByPhone(String phoneNumber)` on `GoalService`/`GoalServiceImpl`.

- [ ] **Step 1: Write the failing tests**

Add to `GoalServiceImplTest.java` (reuse the existing `userId`/`user` fields from `setUp()` if their phone number isn't set — otherwise build a dedicated user, matching the pattern below):

```java
@Test
void listByPhone_success_delegatesToList() {
    var phone = "+5575900000012";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("wg@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    when(goalRepository.findByUserIdOrderByCreatedAtAsc(u.getId())).thenReturn(List.of(goal));

    List<GoalResponse> result = service.listByPhone(phone);

    assertThat(result).hasSize(1);
}

@Test
void listByPhone_phoneNotLinked_throws() {
    var phone = "+5575900000013";
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.listByPhone(phone))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.PhoneNotLinkedException.class);
}

@Test
void listByPhone_nonPro_throwsFeatureLocked() {
    var phone = "+5575900000014";
    User u = User.builder().id(UUID.randomUUID()).name("W").email("wg2@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    org.mockito.Mockito.doThrow(new com.piggy.piggyfinance.exceptions.FeatureLockedException(
                    "This feature requires the PRO plan", com.piggy.piggyfinance.enums.SubscriptionTier.PRO))
            .when(entitlementService).requireTier(u.getId(), com.piggy.piggyfinance.enums.SubscriptionTier.PRO);

    assertThatThrownBy(() -> service.listByPhone(phone))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
}
```

(`goal` here is the existing `goal` field built in `setUp()`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest"`
Expected: FAIL — `listByPhone` doesn't exist.

- [ ] **Step 3: Add the method to the `GoalService` interface**

```java
    List<GoalResponse> listByPhone(String phoneNumber);
```

- [ ] **Step 4: Implement in `GoalServiceImpl`**

Add the two imports `PhoneNotLinkedException` and `SubscriptionTier` (the latter is already imported), then:

```java
    @Override
    public List<GoalResponse> listByPhone(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new com.piggy.piggyfinance.exceptions.PhoneNotLinkedException(
                        "No account linked to this phone number. Please link your WhatsApp in the app."));
        entitlementService.requireTier(user.getId(), SubscriptionTier.PRO);
        return list(user.getId());
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest"`
Expected: PASS

- [ ] **Step 6: Wire the controller endpoint**

In `GoalController.java`, add:

```java
    @GetMapping("/whatsapp")
    @ResponseStatus(HttpStatus.OK)
    public List<GoalResponse> listByPhone(@RequestParam String phoneNumber) {
        return goalService.listByPhone(phoneNumber);
    }
```

- [ ] **Step 7: Add the route to `ApiKeyAuthFilter`**

Add `"/api/v1/goals/whatsapp"` to `API_KEY_PATHS`.

- [ ] **Step 8: Build to confirm everything compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/GoalService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/GoalController.java \
        src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java \
        src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java
git commit -m "feat: add GET /goals/whatsapp for n8n AI agent"
```

---

### Task 7: `GET /billing/whatsapp/status`

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/model/responses/WhatsAppSubscriptionStatusResponse.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/BillingService.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/BillingController.java`
- Modify: `src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java`

**Interfaces:**
- Consumes: existing `SubscriptionRepository.findByUserId(UUID)`, existing `UserRepository.findByPhoneNumber(String)`.
- Produces: `WhatsAppSubscriptionStatusResponse getStatusByPhone(String phoneNumber)` on `BillingService`/`BillingServiceImpl`. `BillingServiceImpl` gains a new `EntitlementService entitlementService` constructor dependency (via `@RequiredArgsConstructor`) — this is a new field, so `BillingServiceImplTest` needs a matching `@Mock EntitlementService entitlementService`.

- [ ] **Step 1: Create the response record**

```java
package com.piggy.piggyfinance.model.responses;

import com.piggy.piggyfinance.enums.SubscriptionStatus;
import com.piggy.piggyfinance.enums.SubscriptionTier;

import java.time.OffsetDateTime;

public record WhatsAppSubscriptionStatusResponse(
        SubscriptionTier tier,
        SubscriptionStatus status,
        OffsetDateTime currentPeriodEnd,
        boolean cancelAtPeriodEnd
) {}
```

- [ ] **Step 2: Add the `EntitlementService` mock to the test class**

In `BillingServiceImplTest.java`, add next to the other `@Mock` fields:

```java
    @Mock com.piggy.piggyfinance.service.EntitlementService entitlementService;
```

- [ ] **Step 3: Write the failing tests**

Add to `BillingServiceImplTest.java`:

```java
@Test
void getStatusByPhone_success_returnsSubscriptionFields() {
    var phone = "+5575900000015";
    User u = User.builder().id(UUID.randomUUID()).name("B").email("b@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    Subscription sub = Subscription.builder()
            .user(u).tier(SubscriptionTier.PRO).status(SubscriptionStatus.ACTIVE)
            .source(SubscriptionSource.STRIPE)
            .currentPeriodEnd(OffsetDateTime.now(ZoneOffset.UTC).plusDays(15))
            .cancelAtPeriodEnd(false)
            .build();
    when(subscriptionRepository.findByUserId(u.getId())).thenReturn(Optional.of(sub));

    var result = service.getStatusByPhone(phone);

    assertThat(result.tier()).isEqualTo(SubscriptionTier.PRO);
    assertThat(result.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(result.cancelAtPeriodEnd()).isFalse();
}

@Test
void getStatusByPhone_phoneNotLinked_throws() {
    var phone = "+5575900000016";
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getStatusByPhone(phone))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.PhoneNotLinkedException.class);
}

@Test
void getStatusByPhone_nonPro_throwsFeatureLocked() {
    var phone = "+5575900000017";
    User u = User.builder().id(UUID.randomUUID()).name("B").email("b2@test.com")
            .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
    when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
    org.mockito.Mockito.doThrow(new com.piggy.piggyfinance.exceptions.FeatureLockedException(
                    "This feature requires the PRO plan", SubscriptionTier.PRO))
            .when(entitlementService).requireTier(u.getId(), SubscriptionTier.PRO);

    assertThatThrownBy(() -> service.getStatusByPhone(phone))
            .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest"`
Expected: FAIL — `getStatusByPhone` doesn't exist.

- [ ] **Step 5: Add the method to the `BillingService` interface**

```java
    WhatsAppSubscriptionStatusResponse getStatusByPhone(String phoneNumber);
```

(add the import `com.piggy.piggyfinance.model.responses.WhatsAppSubscriptionStatusResponse`)

- [ ] **Step 6: Implement in `BillingServiceImpl`**

Add the new field (Lombok's `@RequiredArgsConstructor` picks it up automatically — no constructor changes needed):

```java
    private final EntitlementService entitlementService;
```

Add the import `com.piggy.piggyfinance.service.EntitlementService`, `com.piggy.piggyfinance.exceptions.PhoneNotLinkedException`, and `com.piggy.piggyfinance.model.responses.WhatsAppSubscriptionStatusResponse`. Then add the method:

```java
    @Override
    public WhatsAppSubscriptionStatusResponse getStatusByPhone(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new PhoneNotLinkedException(
                        "No account linked to this phone number. Please link your WhatsApp in the app."));
        entitlementService.requireTier(user.getId(), SubscriptionTier.PRO);

        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseThrow(() -> new UserNotFoundException("No subscription for user: " + user.getId()));

        return new WhatsAppSubscriptionStatusResponse(
                subscription.getTier(),
                subscription.getStatus(),
                subscription.getCurrentPeriodEnd(),
                subscription.isCancelAtPeriodEnd()
        );
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.BillingServiceImplTest"`
Expected: PASS

- [ ] **Step 8: Wire the controller endpoint**

In `BillingController.java`, add:

```java
    @GetMapping("/whatsapp/status")
    @ResponseStatus(HttpStatus.OK)
    public WhatsAppSubscriptionStatusResponse whatsappStatus(@RequestParam String phoneNumber) {
        return billingService.getStatusByPhone(phoneNumber);
    }
```

(add the import `com.piggy.piggyfinance.model.responses.WhatsAppSubscriptionStatusResponse`)

- [ ] **Step 9: Add the route to `ApiKeyAuthFilter`**

Add `"/api/v1/billing/whatsapp/status"` to `API_KEY_PATHS`.

- [ ] **Step 10: Build to confirm everything compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/model/responses/WhatsAppSubscriptionStatusResponse.java \
        src/main/java/com/piggy/piggyfinance/service/BillingService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/BillingServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/BillingController.java \
        src/main/java/com/piggy/piggyfinance/config/ApiKeyAuthFilter.java \
        src/test/java/com/piggy/piggyfinance/service/BillingServiceImplTest.java
git commit -m "feat: add GET /billing/whatsapp/status for n8n AI agent"
```

---

### Task 8: Full regression pass and memory/spec sync

**Files:**
- None (verification only), plus optional memory update.

**Interfaces:**
- Consumes: everything built in Tasks 1–7.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including the pre-existing suite — this confirms no regression in `createWhatsAppTransaction`, billing, goals, and everywhere else).

- [ ] **Step 2: Run a full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manually sanity-check the 6 new routes are present**

```bash
grep -n "whatsapp/summary\|whatsapp/last\|whatsapp/status\|@GetMapping(\"/whatsapp\")" \
  src/main/java/com/piggy/piggyfinance/controller/TransactionController.java \
  src/main/java/com/piggy/piggyfinance/controller/GoalController.java \
  src/main/java/com/piggy/piggyfinance/controller/BillingController.java
```

Expected: all 6 mappings show up (summary GET, last GET/PATCH/DELETE, goals GET, billing status GET).

- [ ] **Step 4: Commit if anything was left uncommitted**

```bash
git status
```

If clean (all prior tasks already committed their own changes), nothing to do here — this step is just a final safety check.
