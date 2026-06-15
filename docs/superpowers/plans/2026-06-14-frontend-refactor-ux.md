# PiggyFinance Frontend Refactor & UX Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the React web app with responsive layout, BottomNav pill redesign, Goals backend integration, and full emoji-to-icon migration across all pages.

**Architecture:** New `AppLayout` component centralises the responsive shell (BottomNav + grid breakpoints); each page drops its per-page `max-w-md` and declares its own internal grid. Goals migrates from local React state to Spring Boot backend with 5 new REST endpoints. A new DELETE endpoint is added to transactions to enable swipe-to-delete.

**Tech Stack:** React 18 + Vite + TypeScript + Tailwind + shadcn/ui + lucide-react + @use-gesture/react (new) — Spring Boot 3 + Java 21 + PostgreSQL + Flyway + Lombok

**Repos:**
- Backend: `/Users/gabrielbraga/Documents/Projects/java/PiggyFinance`
- Frontend: `/Users/gabrielbraga/Documents/Projects/flutter/piggyapp`

---

## File Map

### Backend — new files
| File | Purpose |
|------|---------|
| `src/main/resources/db/migration/V7__goals.sql` | Creates `goals` table |
| `src/main/java/…/model/Goal.java` | Goal JPA entity |
| `src/main/java/…/model/requests/CreateGoalRequest.java` | POST body |
| `src/main/java/…/model/requests/UpdateGoalRequest.java` | PUT body |
| `src/main/java/…/model/requests/GoalProgressRequest.java` | PATCH body |
| `src/main/java/…/model/responses/GoalResponse.java` | API response |
| `src/main/java/…/repository/GoalRepository.java` | JPA repo |
| `src/main/java/…/service/GoalService.java` | Service interface |
| `src/main/java/…/service/impl/GoalServiceImpl.java` | Implementation |
| `src/main/java/…/controller/GoalController.java` | REST controller |
| `src/test/java/…/service/GoalServiceImplTest.java` | Unit tests |

### Backend — modified files
| File | Change |
|------|--------|
| `src/main/java/…/enums/CategoryType.java` | Add SALARY, FREELANCE, INVESTMENT, GIFT |
| `src/main/java/…/service/TransactionService.java` | Add `deleteTransaction` |
| `src/main/java/…/service/impl/TransactionServiceImpl.java` | Implement `deleteTransaction` |
| `src/main/java/…/controller/TransactionController.java` | Add DELETE endpoint |

### Frontend — new files
| File | Purpose |
|------|---------|
| `src/components/AppLayout.tsx` | Responsive shell wrapper |
| `src/services/goalsService.ts` | Goals API calls |
| `src/hooks/useGoals.ts` | React Query hooks for goals |

### Frontend — modified files
| File | Change |
|------|--------|
| `package.json` | Add @use-gesture/react |
| `src/App.tsx` | Remove root `max-w-md`, wrap pages in AppLayout |
| `src/components/BottomNav.tsx` | Pill style redesign |
| `src/contexts/FinanceContext.tsx` | Remove goals state; expose `deleteTransaction` |
| `src/pages/Goals.tsx` | Full rebuild with backend |
| `src/pages/Transactions.tsx` | Always-visible filters, swipe-to-delete |
| `src/pages/AddTransaction.tsx` | Income categories, dynamic colour |
| `src/pages/Dashboard.tsx` | Responsive grid, no `max-w-md` |
| `src/pages/Profile.tsx` | Avatar layout, inline toggle |
| `src/pages/Charts.tsx` | Remove `max-w-md`, filter chips |
| `src/pages/Welcome.tsx` | Remove emojis |
| `src/pages/Login.tsx` | Remove emojis |
| `src/pages/Register.tsx` | Remove emojis |
| `src/services/api.ts` | Add `deleteTransaction`, income category types |

### Frontend — deleted files
| File | Reason |
|------|--------|
| `src/components/CategoryIcon.tsx` | Replaced by lucide-react inline |

---

## Task 1: Goals DB migration

**Repo:** Backend (`/Users/gabrielbraga/Documents/Projects/java/PiggyFinance`)

**Files:**
- Create: `src/main/resources/db/migration/V7__goals.sql`

- [ ] **Step 1: Verify no V7 migration exists**

```bash
ls src/main/resources/db/migration/
```
Expected: highest file is `V6__fix_whatsapp_code_length.sql`. If a V7 already exists, use V8.

- [ ] **Step 2: Create migration**

```sql
-- src/main/resources/db/migration/V7__goals.sql
CREATE TABLE goals (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    target_amount   DECIMAL(19,2) NOT NULL CHECK (target_amount > 0),
    current_amount  DECIMAL(19,2) NOT NULL DEFAULT 0 CHECK (current_amount >= 0),
    icon_name  VARCHAR(50)  NOT NULL DEFAULT 'Target',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_goals_user_id ON goals(user_id);
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V7__goals.sql
git commit -m "feat: add goals table migration V7"
```

---

## Task 2: Goal entity + DTOs

**Repo:** Backend

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/model/Goal.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/requests/CreateGoalRequest.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/requests/UpdateGoalRequest.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/requests/GoalProgressRequest.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/responses/GoalResponse.java`

- [ ] **Step 1: Create Goal entity**

```java
// src/main/java/com/piggy/piggyfinance/model/Goal.java
package com.piggy.piggyfinance.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "goals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @NotNull
    @Positive
    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    @NotNull
    @Column(name = "current_amount", nullable = false)
    private BigDecimal currentAmount;

    @NotBlank
    @Column(name = "icon_name", nullable = false)
    private String iconName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (currentAmount == null) currentAmount = BigDecimal.ZERO;
    }
}
```

- [ ] **Step 2: Create request records**

```java
// src/main/java/com/piggy/piggyfinance/model/requests/CreateGoalRequest.java
package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record CreateGoalRequest(
    @NotBlank String name,
    @NotNull @Positive BigDecimal targetAmount,
    BigDecimal currentAmount,
    @NotBlank String iconName
) {}
```

```java
// src/main/java/com/piggy/piggyfinance/model/requests/UpdateGoalRequest.java
package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record UpdateGoalRequest(
    @NotBlank String name,
    @NotNull @Positive BigDecimal targetAmount,
    @NotBlank String iconName
) {}
```

```java
// src/main/java/com/piggy/piggyfinance/model/requests/GoalProgressRequest.java
package com.piggy.piggyfinance.model.requests;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record GoalProgressRequest(
    @NotNull @Positive BigDecimal amount
) {}
```

- [ ] **Step 3: Create GoalResponse**

```java
// src/main/java/com/piggy/piggyfinance/model/responses/GoalResponse.java
package com.piggy.piggyfinance.model.responses;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record GoalResponse(
    UUID id,
    String name,
    BigDecimal targetAmount,
    BigDecimal currentAmount,
    String iconName,
    LocalDateTime createdAt
) {}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/model/
git commit -m "feat: add Goal entity and goal request/response DTOs"
```

---

## Task 3: GoalRepository + GoalService interface

**Repo:** Backend

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/repository/GoalRepository.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/GoalService.java`

- [ ] **Step 1: Create GoalRepository**

```java
// src/main/java/com/piggy/piggyfinance/repository/GoalRepository.java
package com.piggy.piggyfinance.repository;

import com.piggy.piggyfinance.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<Goal> findByIdAndUserId(UUID id, UUID userId);
}
```

- [ ] **Step 2: Create GoalService interface**

```java
// src/main/java/com/piggy/piggyfinance/service/GoalService.java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;

import java.util.List;
import java.util.UUID;

public interface GoalService {
    GoalResponse create(CreateGoalRequest request, UUID userId);
    List<GoalResponse> list(UUID userId);
    GoalResponse update(UUID goalId, UpdateGoalRequest request, UUID userId);
    void delete(UUID goalId, UUID userId);
    GoalResponse addProgress(UUID goalId, GoalProgressRequest request, UUID userId);
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/repository/GoalRepository.java \
        src/main/java/com/piggy/piggyfinance/service/GoalService.java
git commit -m "feat: add GoalRepository and GoalService interface"
```

---

## Task 4: GoalServiceImpl

**Repo:** Backend

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java`

- [ ] **Step 1: Write GoalServiceImpl**

```java
// src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java
package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.GoalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoalServiceImpl implements GoalService {

    private final GoalRepository goalRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public GoalResponse create(CreateGoalRequest request, UUID userId) {
        User user = findUser(userId);
        BigDecimal initial = request.currentAmount() != null ? request.currentAmount() : BigDecimal.ZERO;
        Goal goal = goalRepository.save(Goal.builder()
                .user(user)
                .name(request.name())
                .targetAmount(request.targetAmount())
                .currentAmount(initial.min(request.targetAmount()))
                .iconName(request.iconName())
                .build());
        return toResponse(goal);
    }

    @Override
    public List<GoalResponse> list(UUID userId) {
        return goalRepository.findByUserIdOrderByCreatedAtAsc(userId)
                .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public GoalResponse update(UUID goalId, UpdateGoalRequest request, UUID userId) {
        Goal goal = findOwned(goalId, userId);
        Goal updated = goalRepository.save(goal.toBuilder()
                .name(request.name())
                .targetAmount(request.targetAmount())
                .iconName(request.iconName())
                .build());
        return toResponse(updated);
    }

    @Override
    @Transactional
    public void delete(UUID goalId, UUID userId) {
        Goal goal = findOwned(goalId, userId);
        goalRepository.delete(goal);
    }

    @Override
    @Transactional
    public GoalResponse addProgress(UUID goalId, GoalProgressRequest request, UUID userId) {
        Goal goal = findOwned(goalId, userId);
        BigDecimal newAmount = goal.getCurrentAmount()
                .add(request.amount())
                .min(goal.getTargetAmount());
        Goal updated = goalRepository.save(goal.toBuilder()
                .currentAmount(newAmount)
                .build());
        return toResponse(updated);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
    }

    private Goal findOwned(UUID goalId, UUID userId) {
        return goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> new UnauthorizedException("Goal not found or access denied"));
    }

    private GoalResponse toResponse(Goal g) {
        return new GoalResponse(
                g.getId(), g.getName(),
                g.getTargetAmount(), g.getCurrentAmount(),
                g.getIconName(), g.getCreatedAt());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/impl/GoalServiceImpl.java
git commit -m "feat: implement GoalServiceImpl with CRUD and progress"
```

---

## Task 5: GoalController

**Repo:** Backend

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/controller/GoalController.java`

- [ ] **Step 1: Write controller**

```java
// src/main/java/com/piggy/piggyfinance/controller/GoalController.java
package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;
import com.piggy.piggyfinance.service.GoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse create(@RequestBody @Valid CreateGoalRequest request,
                               @AuthenticationPrincipal UUID userId) {
        return goalService.create(request, userId);
    }

    @GetMapping
    public List<GoalResponse> list(@AuthenticationPrincipal UUID userId) {
        return goalService.list(userId);
    }

    @PutMapping("/{id}")
    public GoalResponse update(@PathVariable UUID id,
                               @RequestBody @Valid UpdateGoalRequest request,
                               @AuthenticationPrincipal UUID userId) {
        return goalService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,
                       @AuthenticationPrincipal UUID userId) {
        goalService.delete(id, userId);
    }

    @PatchMapping("/{id}/progress")
    public GoalResponse addProgress(@PathVariable UUID id,
                                    @RequestBody @Valid GoalProgressRequest request,
                                    @AuthenticationPrincipal UUID userId) {
        return goalService.addProgress(id, request, userId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/controller/GoalController.java
git commit -m "feat: add GoalController REST endpoints"
```

---

## Task 6: GoalServiceImplTest

**Repo:** Backend

**Files:**
- Create: `src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java`

- [ ] **Step 1: Write tests**

```java
// src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.GoalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalServiceImplTest {

    @Mock GoalRepository goalRepository;
    @Mock UserRepository userRepository;
    @InjectMocks GoalServiceImpl service;

    private UUID userId;
    private UUID goalId;
    private User user;
    private Goal goal;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        goalId = UUID.randomUUID();
        user = User.builder().id(userId).name("Test").email("test@test.com").password("x").build();
        goal = Goal.builder()
                .id(goalId).user(user).name("Casa própria")
                .targetAmount(new BigDecimal("50000"))
                .currentAmount(new BigDecimal("10000"))
                .iconName("Home")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void create_savesGoalWithInitialAmount() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(goalRepository.save(any())).thenReturn(goal);

        CreateGoalRequest req = new CreateGoalRequest("Casa própria", new BigDecimal("50000"), new BigDecimal("10000"), "Home");
        GoalResponse response = service.create(req, userId);

        assertThat(response.name()).isEqualTo("Casa própria");
        verify(goalRepository).save(any(Goal.class));
    }

    @Test
    void create_clampsInitialAmountToTarget() {
        BigDecimal overAmount = new BigDecimal("99999");
        Goal clamped = goal.toBuilder().currentAmount(new BigDecimal("50000")).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(goalRepository.save(any())).thenReturn(clamped);

        CreateGoalRequest req = new CreateGoalRequest("Casa própria", new BigDecimal("50000"), overAmount, "Home");
        service.create(req, userId);

        verify(goalRepository).save(argThat(g -> g.getCurrentAmount().compareTo(new BigDecimal("50000")) == 0));
    }

    @Test
    void list_returnsUserGoals() {
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(goal));
        List<GoalResponse> result = service.list(userId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Casa própria");
    }

    @Test
    void addProgress_addsAmountAndClampsAtTarget() {
        Goal updated = goal.toBuilder().currentAmount(new BigDecimal("50000")).build();
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenReturn(updated);

        GoalProgressRequest req = new GoalProgressRequest(new BigDecimal("99999"));
        GoalResponse response = service.addProgress(goalId, req, userId);

        assertThat(response.currentAmount()).isEqualByComparingTo("50000");
    }

    @Test
    void delete_throwsWhenGoalNotOwned() {
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(goalId, userId))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void update_updatesNameAndTarget() {
        Goal updated = goal.toBuilder().name("Novo nome").targetAmount(new BigDecimal("60000")).build();
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenReturn(updated);

        UpdateGoalRequest req = new UpdateGoalRequest("Novo nome", new BigDecimal("60000"), "Home");
        GoalResponse response = service.update(goalId, req, userId);

        assertThat(response.name()).isEqualTo("Novo nome");
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd /Users/gabrielbraga/Documents/Projects/java/PiggyFinance
./gradlew test --tests "com.piggy.piggyfinance.service.GoalServiceImplTest"
```
Expected: 6 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/piggy/piggyfinance/service/GoalServiceImplTest.java
git commit -m "test: add GoalServiceImplTest unit tests"
```

---

## Task 7: Add DELETE /transactions/{id} endpoint

**Repo:** Backend

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/service/TransactionService.java`
- Modify: `src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java`
- Modify: `src/main/java/com/piggy/piggyfinance/controller/TransactionController.java`

- [ ] **Step 1: Add to TransactionService interface** — append after the last method:

```java
void deleteTransaction(UUID transactionId, UUID userId);
```

- [ ] **Step 2: Implement in TransactionServiceImpl** — add this method to the class:

```java
@Override
@Transactional
public void deleteTransaction(UUID transactionId, UUID userId) {
    Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new BusinessException("Transação não encontrada"));
    if (!transaction.getUser().getId().equals(userId)) {
        throw new UnauthorizedException("Acesso negado");
    }
    transactionRepository.delete(transaction);
}
```

- [ ] **Step 3: Add DELETE to TransactionController** — append after the `summary` method:

```java
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable UUID id,
                   @AuthenticationPrincipal UUID userId) {
    transactionService.deleteTransaction(id, userId);
}
```

- [ ] **Step 4: Run all tests**

```bash
./gradlew test
```
Expected: all existing tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/TransactionService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/TransactionServiceImpl.java \
        src/main/java/com/piggy/piggyfinance/controller/TransactionController.java
git commit -m "feat: add DELETE /api/v1/transactions/{id} endpoint"
```

---

## Task 8: Add income category types

**Repo:** Backend

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/enums/CategoryType.java`

- [ ] **Step 1: Add income values to CategoryType**

```java
package com.piggy.piggyfinance.enums;

public enum CategoryType {
    // Expense categories
    FOOD,
    TRANSPORT,
    RENT,
    HEALTH,
    EDUCATION,
    LEISURE,
    SUBSCRIPTIONS,
    TRAVEL,
    OTHER,
    // Income categories
    SALARY,
    FREELANCE,
    INVESTMENT,
    GIFT
}
```

No migration needed — the `category` column is `VARCHAR(50)`, not a Postgres enum.

- [ ] **Step 2: Run tests**

```bash
./gradlew test
```
Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/enums/CategoryType.java
git commit -m "feat: add income category types (SALARY, FREELANCE, INVESTMENT, GIFT)"
```

---

## Task 9: AppLayout component

**Repo:** Frontend (`/Users/gabrielbraga/Documents/Projects/flutter/piggyapp`)

**Files:**
- Create: `src/components/AppLayout.tsx`

- [ ] **Step 1: Create AppLayout**

```tsx
// src/components/AppLayout.tsx
import { ReactNode } from 'react';

interface AppLayoutProps {
  children: ReactNode;
}

const AppLayout = ({ children }: AppLayoutProps) => (
  <div className="min-h-screen bg-background w-full">
    <div className="mx-auto max-w-5xl px-4 md:px-8 pt-6 pb-28">
      {children}
    </div>
  </div>
);

export default AppLayout;
```

- [ ] **Step 2: Commit**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
git add src/components/AppLayout.tsx
git commit -m "feat: add AppLayout responsive shell component"
```

---

## Task 10: BottomNav pill redesign

**Repo:** Frontend

**Files:**
- Modify: `src/components/BottomNav.tsx`

- [ ] **Step 1: Rewrite BottomNav.tsx**

```tsx
// src/components/BottomNav.tsx
import { LayoutDashboard, Receipt, PlusCircle, BarChart3, Target } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { cn } from '@/lib/utils';

const tabs = [
  { path: '/',             icon: LayoutDashboard, label: 'Início' },
  { path: '/transactions', icon: Receipt,          label: 'Transações' },
  { path: '/add',          icon: PlusCircle,        label: 'Adicionar' },
  { path: '/charts',       icon: BarChart3,          label: 'Gráficos' },
  { path: '/goals',        icon: Target,             label: 'Metas' },
];

const BottomNav = () => {
  const location = useLocation();
  const navigate = useNavigate();

  return (
    <nav className="fixed bottom-4 left-0 right-0 z-50 px-4">
      <div className="max-w-lg mx-auto bg-card/95 backdrop-blur-lg rounded-2xl shadow-nav border border-border/50 flex items-center justify-around h-16 px-3">
        {tabs.map(({ path, icon: Icon, label }) => {
          const active = location.pathname === path;
          const isFab = path === '/add';

          if (isFab) {
            return (
              <button
                key={path}
                onClick={() => navigate(path)}
                className={cn(
                  'flex flex-col items-center gap-1 -translate-y-3',
                )}
                aria-label="Adicionar transação"
              >
                <div className={cn(
                  'w-12 h-12 rounded-full flex items-center justify-center shadow-lg ring-4 ring-background transition-transform duration-200',
                  active ? 'scale-110' : 'scale-100',
                  'bg-primary'
                )}>
                  <Icon size={22} className="text-primary-foreground" />
                </div>
                <span className="text-[10px] font-medium text-muted-foreground">{label}</span>
              </button>
            );
          }

          return (
            <button
              key={path}
              onClick={() => navigate(path)}
              className="flex items-center transition-all duration-300 ease-out"
              aria-label={label}
            >
              {active ? (
                <span className="flex items-center gap-2 bg-primary text-primary-foreground rounded-full px-4 py-2 text-sm font-semibold">
                  <Icon size={16} />
                  {label}
                </span>
              ) : (
                <span className="p-2 text-muted-foreground hover:text-foreground">
                  <Icon size={20} strokeWidth={1.6} />
                </span>
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
};

export default BottomNav;
```

- [ ] **Step 2: Commit**

```bash
git add src/components/BottomNav.tsx
git commit -m "feat: redesign BottomNav with pill-style active indicator"
```

---

## Task 11: Update App.tsx

**Repo:** Frontend

**Files:**
- Modify: `src/App.tsx`

- [ ] **Step 1: Update App.tsx** — replace the root `<div>` and add AppLayout to authenticated routes:

```tsx
// src/App.tsx
import { Toaster } from "@/components/ui/toaster";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, useLocation, Navigate } from "react-router-dom";
import { FinanceProvider } from "@/contexts/FinanceContext";
import { getAuthToken } from "@/services/api";
import AppLayout from "@/components/AppLayout";
import BottomNav from "@/components/BottomNav";
import PageTransition from "@/components/PageTransition";
import Dashboard from "./pages/Dashboard";
import Transactions from "./pages/Transactions";
import AddTransaction from "./pages/AddTransaction";
import Charts from "./pages/Charts";
import Goals from "./pages/Goals";
import Profile from "./pages/Profile";
import NotFound from "./pages/NotFound";
import Welcome from "./pages/Welcome";
import Login from "./pages/Login";
import Register from "./pages/Register";

const queryClient = new QueryClient();

const ProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const token = getAuthToken();
  if (!token) return <Navigate to="/welcome" replace />;
  return <AppLayout>{children}</AppLayout>;
};

const AppRoutes = () => {
  const location = useLocation();
  const isAuthRoute = ['/welcome', '/login', '/register'].includes(location.pathname);

  return (
    <>
      <PageTransition key={location.pathname}>
        <Routes location={location}>
          <Route path="/welcome" element={<Welcome />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/" element={<ProtectedRoute><Dashboard /></ProtectedRoute>} />
          <Route path="/transactions" element={<ProtectedRoute><Transactions /></ProtectedRoute>} />
          <Route path="/add" element={<ProtectedRoute><AddTransaction /></ProtectedRoute>} />
          <Route path="/charts" element={<ProtectedRoute><Charts /></ProtectedRoute>} />
          <Route path="/goals" element={<ProtectedRoute><Goals /></ProtectedRoute>} />
          <Route path="/profile" element={<ProtectedRoute><Profile /></ProtectedRoute>} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </PageTransition>
      {!isAuthRoute && <BottomNav />}
    </>
  );
};

const App = () => (
  <QueryClientProvider client={queryClient}>
    <TooltipProvider>
      <Toaster />
      <Sonner />
      <FinanceProvider>
        <BrowserRouter>
          <div className="min-h-screen bg-background">
            <AppRoutes />
          </div>
        </BrowserRouter>
      </FinanceProvider>
    </TooltipProvider>
  </QueryClientProvider>
);

export default App;
```

- [ ] **Step 2: Start dev server and verify layout renders**

```bash
npm run dev
```
Open `http://localhost:5173`. Log in and confirm:
- App fills the screen (no narrow column)
- BottomNav shows pill on active tab
- No layout breakage

- [ ] **Step 3: Commit**

```bash
git add src/App.tsx
git commit -m "feat: integrate AppLayout and update root div for responsive layout"
```

---

## Task 12: Goals API service + React Query hooks

**Repo:** Frontend

**Files:**
- Modify: `src/services/api.ts`
- Create: `src/services/goalsService.ts`
- Create: `src/hooks/useGoals.ts`

- [ ] **Step 1: Add deleteTransaction to api.ts** — append to the end of `src/services/api.ts`:

```ts
export async function deleteTransaction(id: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/transactions/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`Erro ao apagar transação: ${res.status}`);
}
```

- [ ] **Step 2: Create goalsService.ts**

```ts
// src/services/goalsService.ts
const BASE_URL = 'https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/v1';

import { getAuthToken } from './api';

const authHeaders = (): HeadersInit => {
  const token = getAuthToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
};

export interface GoalResponse {
  id: string;
  name: string;
  targetAmount: number;
  currentAmount: number;
  iconName: string;
  createdAt: string;
}

export interface CreateGoalRequest {
  name: string;
  targetAmount: number;
  currentAmount?: number;
  iconName: string;
}

export interface UpdateGoalRequest {
  name: string;
  targetAmount: number;
  iconName: string;
}

export async function listGoals(): Promise<GoalResponse[]> {
  const res = await fetch(`${BASE_URL}/goals`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Erro ao listar metas: ${res.status}`);
  return res.json();
}

export async function createGoal(data: CreateGoalRequest): Promise<GoalResponse> {
  const res = await fetch(`${BASE_URL}/goals`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`Erro ao criar meta: ${res.status}`);
  return res.json();
}

export async function updateGoal(id: string, data: UpdateGoalRequest): Promise<GoalResponse> {
  const res = await fetch(`${BASE_URL}/goals/${id}`, {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`Erro ao editar meta: ${res.status}`);
  return res.json();
}

export async function deleteGoal(id: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/goals/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`Erro ao apagar meta: ${res.status}`);
}

export async function addGoalProgress(id: string, amount: number): Promise<GoalResponse> {
  const res = await fetch(`${BASE_URL}/goals/${id}/progress`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify({ amount }),
  });
  if (!res.ok) throw new Error(`Erro ao aportar na meta: ${res.status}`);
  return res.json();
}
```

- [ ] **Step 3: Create useGoals.ts**

```ts
// src/hooks/useGoals.ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listGoals, createGoal, updateGoal, deleteGoal, addGoalProgress,
  CreateGoalRequest, UpdateGoalRequest,
} from '@/services/goalsService';
import { getAuthToken } from '@/services/api';

const GOALS_KEY = ['goals'];

export const useGoals = () =>
  useQuery({
    queryKey: GOALS_KEY,
    queryFn: listGoals,
    enabled: !!getAuthToken(),
  });

export const useCreateGoal = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateGoalRequest) => createGoal(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: GOALS_KEY }),
  });
};

export const useUpdateGoal = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateGoalRequest }) => updateGoal(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: GOALS_KEY }),
  });
};

export const useDeleteGoal = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteGoal(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: GOALS_KEY }),
  });
};

export const useAddGoalProgress = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, amount }: { id: string; amount: number }) => addGoalProgress(id, amount),
    onSuccess: () => qc.invalidateQueries({ queryKey: GOALS_KEY }),
  });
};
```

- [ ] **Step 4: Commit**

```bash
git add src/services/api.ts src/services/goalsService.ts src/hooks/useGoals.ts
git commit -m "feat: add goals API service and React Query hooks"
```

---

## Task 13: Remove goals from FinanceContext

**Repo:** Frontend

**Files:**
- Modify: `src/contexts/FinanceContext.tsx`

- [ ] **Step 1: Remove all goals state and methods from FinanceContext.tsx**

Replace the entire file:

```tsx
// src/contexts/FinanceContext.tsx
import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import {
  createTransaction as apiCreateTransaction,
  deleteTransaction as apiDeleteTransaction,
  listTransactions as apiListTransactions,
  getTransactionSummary as apiGetSummary,
  getCurrentUser,
  TransactionResponse,
  CreateTransactionRequest,
  getAuthToken,
} from '@/services/api';
import { getApiErrorMessage } from '@/lib/api-error';
import { toastError } from '@/lib/toast';

export type TransactionType = 'income' | 'expense';

export interface Transaction {
  id: string;
  type: TransactionType;
  amount: number;
  description: string;
  category: string;
  date: string;
  note?: string;
}

interface FinanceContextType {
  userName: string;
  transactions: Transaction[];
  addTransaction: (t: Omit<Transaction, 'id'>) => Promise<void>;
  deleteTransaction: (id: string) => Promise<void>;
  totalIncome: number;
  totalExpense: number;
  balance: number;
  loading: boolean;
  refreshTransactions: () => void;
}

export const expenseCategories = [
  'Alimentação', 'Transporte', 'Moradia', 'Saúde',
  'Educação', 'Lazer', 'Assinaturas', 'Viagens', 'Outros',
];

export const incomeCategories = [
  'Salário', 'Freelance', 'Investimento', 'Presente', 'Outros',
];

export const categories = expenseCategories;

const categoryToEnum: Record<string, string> = {
  'Alimentação': 'FOOD',
  'Transporte': 'TRANSPORT',
  'Moradia': 'RENT',
  'Saúde': 'HEALTH',
  'Educação': 'EDUCATION',
  'Lazer': 'LEISURE',
  'Assinaturas': 'SUBSCRIPTIONS',
  'Viagens': 'TRAVEL',
  'Outros': 'OTHER',
  'Salário': 'SALARY',
  'Freelance': 'FREELANCE',
  'Investimento': 'INVESTMENT',
  'Presente': 'GIFT',
};

const enumToCategory: Record<string, string> = {};
Object.entries(categoryToEnum).forEach(([k, v]) => {
  enumToCategory[v] = k;
  enumToCategory[v.toLowerCase()] = k;
});

function mapApiTransaction(t: TransactionResponse): Transaction {
  const type = t.type.toLowerCase() as TransactionType;
  let category: string;
  if (t.category) {
    category = enumToCategory[t.category] || enumToCategory[t.category.toUpperCase()] || t.category;
  } else {
    category = type === 'income' ? 'Salário' : 'Outros';
  }
  return {
    id: t.id,
    type,
    amount: t.amount,
    description: t.description,
    category,
    date: t.timestamp ? t.timestamp.split('T')[0] : new Date().toISOString().split('T')[0],
  };
}

const FinanceContext = createContext<FinanceContextType | null>(null);

export const useFinance = () => {
  const ctx = useContext(FinanceContext);
  if (!ctx) throw new Error('useFinance must be inside FinanceProvider');
  return ctx;
};

export const FinanceProvider = ({ children }: { children: ReactNode }) => {
  const [userName, setUserName] = useState('');
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [totalIncome, setTotalIncome] = useState(0);
  const [totalExpense, setTotalExpense] = useState(0);
  const [balance, setBalance] = useState(0);
  const [loading, setLoading] = useState(false);

  const hasToken = !!getAuthToken();

  const refreshTransactions = useCallback(async () => {
    if (!hasToken) return;
    setLoading(true);
    try {
      const [page, summary, user] = await Promise.all([
        apiListTransactions(0, 50),
        apiGetSummary(
          new Date(new Date().getFullYear(), new Date().getMonth(), 1).toISOString().split('T')[0],
          new Date().toISOString().split('T')[0],
        ),
        getCurrentUser(),
      ]);
      setTransactions(page.content.map(mapApiTransaction));
      setTotalIncome(summary.income);
      setTotalExpense(summary.expense);
      setBalance(summary.balance);
      setUserName(user.name);
    } catch (err) {
      toastError(getApiErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [hasToken]);

  useEffect(() => { refreshTransactions(); }, [refreshTransactions]);

  const addTransaction = async (t: Omit<Transaction, 'id'>) => {
    const req: CreateTransactionRequest = {
      description: t.description,
      amount: t.amount,
      type: t.type.toUpperCase() as 'INCOME' | 'EXPENSE',
      category: categoryToEnum[t.category] || 'OTHER',
    };
    await apiCreateTransaction(req);
    refreshTransactions();
  };

  const deleteTransaction = async (id: string) => {
    await apiDeleteTransaction(id);
    setTransactions(prev => prev.filter(t => t.id !== id));
  };

  return (
    <FinanceContext.Provider value={{
      userName, transactions, addTransaction, deleteTransaction,
      totalIncome, totalExpense, balance, loading, refreshTransactions,
    }}>
      {children}
    </FinanceContext.Provider>
  );
};
```

- [ ] **Step 2: Commit**

```bash
git add src/contexts/FinanceContext.tsx
git commit -m "refactor: remove goals from FinanceContext, add deleteTransaction, add income categories"
```

---

## Task 14: Goals page rebuild

**Repo:** Frontend

**Files:**
- Modify: `src/pages/Goals.tsx`

- [ ] **Step 1: Rewrite Goals.tsx**

```tsx
// src/pages/Goals.tsx
import { useState } from 'react';
import {
  Home, Car, Plane, Smartphone, GraduationCap, PiggyBank,
  TreePalm, Target, Bike, Heart, ShoppingBag, Laptop,
  Plus, Pencil, Trash2, MoreVertical, TrendingUp, Check,
} from 'lucide-react';
import { useGoals, useCreateGoal, useUpdateGoal, useDeleteGoal, useAddGoalProgress } from '@/hooks/useGoals';
import { GoalResponse } from '@/services/goalsService';
import { Progress } from '@/components/ui/progress';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { toastError } from '@/lib/toast';
import { getApiErrorMessage } from '@/lib/api-error';

const GOAL_ICONS = [
  { name: 'Home', Icon: Home },
  { name: 'Car', Icon: Car },
  { name: 'Plane', Icon: Plane },
  { name: 'Smartphone', Icon: Smartphone },
  { name: 'GraduationCap', Icon: GraduationCap },
  { name: 'PiggyBank', Icon: PiggyBank },
  { name: 'TreePalm', Icon: TreePalm },
  { name: 'Target', Icon: Target },
  { name: 'Bike', Icon: Bike },
  { name: 'Heart', Icon: Heart },
  { name: 'ShoppingBag', Icon: ShoppingBag },
  { name: 'Laptop', Icon: Laptop },
] as const;

type IconName = typeof GOAL_ICONS[number]['name'];

function GoalIcon({ name, className }: { name: string; className?: string }) {
  const match = GOAL_ICONS.find(i => i.name === name);
  const Icon = match?.Icon ?? Target;
  return <Icon size={18} className={className} />;
}

const fmt = (v: number) => v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

interface GoalFormState {
  name: string;
  targetAmount: string;
  currentAmount: string;
  iconName: IconName;
}

const emptyForm = (): GoalFormState => ({ name: '', targetAmount: '', currentAmount: '', iconName: 'Target' });

const Goals = () => {
  const { data: goals = [], isLoading } = useGoals();
  const createGoal = useCreateGoal();
  const updateGoal = useUpdateGoal();
  const deleteGoal = useDeleteGoal();
  const addProgress = useAddGoalProgress();

  const [createOpen, setCreateOpen] = useState(false);
  const [editGoal, setEditGoal] = useState<GoalResponse | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [investGoal, setInvestGoal] = useState<GoalResponse | null>(null);
  const [form, setForm] = useState<GoalFormState>(emptyForm());
  const [errors, setErrors] = useState<{ name?: string; target?: string }>({});
  const [investAmount, setInvestAmount] = useState('');
  const [investError, setInvestError] = useState('');

  const totalSaved = goals.reduce((s, g) => s + g.currentAmount, 0);
  const completed = goals.filter(g => g.currentAmount >= g.targetAmount);
  const inProgress = goals.filter(g => g.currentAmount < g.targetAmount);
  const nextGoal = [...inProgress].sort((a, b) =>
    (b.currentAmount / b.targetAmount) - (a.currentAmount / a.targetAmount)
  )[0];

  const validate = (f: GoalFormState) => {
    const e: { name?: string; target?: string } = {};
    if (!f.name.trim()) e.name = 'Informe um nome';
    if (!f.targetAmount || parseFloat(f.targetAmount) <= 0) e.target = 'Informe o valor alvo';
    return e;
  };

  const openCreate = () => { setForm(emptyForm()); setErrors({}); setCreateOpen(true); };

  const openEdit = (goal: GoalResponse) => {
    setForm({ name: goal.name, targetAmount: goal.targetAmount.toString(), currentAmount: goal.currentAmount.toString(), iconName: goal.iconName as IconName });
    setErrors({});
    setEditGoal(goal);
  };

  const handleCreate = async () => {
    const e = validate(form);
    if (Object.keys(e).length) { setErrors(e); return; }
    try {
      await createGoal.mutateAsync({ name: form.name, targetAmount: parseFloat(form.targetAmount), currentAmount: parseFloat(form.currentAmount) || 0, iconName: form.iconName });
      setCreateOpen(false);
    } catch (err) { toastError(getApiErrorMessage(err)); }
  };

  const handleEdit = async () => {
    if (!editGoal) return;
    const e = validate(form);
    if (Object.keys(e).length) { setErrors(e); return; }
    try {
      await updateGoal.mutateAsync({ id: editGoal.id, data: { name: form.name, targetAmount: parseFloat(form.targetAmount), iconName: form.iconName } });
      setEditGoal(null);
    } catch (err) { toastError(getApiErrorMessage(err)); }
  };

  const handleDelete = async () => {
    if (!deleteId) return;
    try {
      await deleteGoal.mutateAsync(deleteId);
    } catch (err) { toastError(getApiErrorMessage(err)); }
    setDeleteId(null);
  };

  const handleInvest = async () => {
    if (!investGoal) return;
    const v = parseFloat(investAmount);
    if (!investAmount || v <= 0) { setInvestError('Informe um valor válido'); return; }
    try {
      await addProgress.mutateAsync({ id: investGoal.id, amount: v });
      setInvestGoal(null);
      setInvestAmount('');
      setInvestError('');
    } catch (err) { toastError(getApiErrorMessage(err)); }
  };

  const GoalFormFields = () => (
    <div className="space-y-4 mt-2">
      <div>
        <label className="text-sm text-muted-foreground mb-2 block">Ícone</label>
        <div className="grid grid-cols-6 gap-2">
          {GOAL_ICONS.map(({ name, Icon }) => (
            <button
              key={name}
              type="button"
              onClick={() => setForm(f => ({ ...f, iconName: name }))}
              className={cn('w-10 h-10 rounded-xl flex items-center justify-center transition-all', form.iconName === name ? 'bg-primary/15 ring-2 ring-primary' : 'bg-muted hover:bg-muted/80')}
            >
              <Icon size={18} className={form.iconName === name ? 'text-primary' : 'text-muted-foreground'} />
            </button>
          ))}
        </div>
      </div>
      <div>
        <Input placeholder="Nome da meta (ex: Casa própria)" value={form.name} onChange={e => { setForm(f => ({ ...f, name: e.target.value })); setErrors(v => ({ ...v, name: '' })); }} className={cn(errors.name && 'ring-2 ring-destructive')} />
        {errors.name && <p className="text-sm text-destructive mt-1">{errors.name}</p>}
      </div>
      <div>
        <Input type="number" placeholder="Valor total (R$)" value={form.targetAmount} onChange={e => { setForm(f => ({ ...f, targetAmount: e.target.value })); setErrors(v => ({ ...v, target: '' })); }} className={cn(errors.target && 'ring-2 ring-destructive')} />
        {errors.target && <p className="text-sm text-destructive mt-1">{errors.target}</p>}
      </div>
    </div>
  );

  if (isLoading) return <div className="flex items-center justify-center py-20 text-muted-foreground text-sm">Carregando metas...</div>;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Metas</h1>
          <p className="text-sm text-muted-foreground mt-0.5">Acompanhe seus objetivos financeiros</p>
        </div>
        <Button onClick={openCreate} size="sm" className="gradient-primary text-primary-foreground gap-1.5">
          <Plus size={16} /> Nova meta
        </Button>
      </div>

      {/* Summary strip */}
      {goals.length > 0 && (
        <div className="grid grid-cols-3 gap-3">
          <div className="bg-card rounded-xl p-3 shadow-card text-center">
            <p className="text-xs text-muted-foreground">Total investido</p>
            <p className="text-sm font-bold text-primary mt-1">{fmt(totalSaved)}</p>
          </div>
          <div className="bg-card rounded-xl p-3 shadow-card text-center">
            <p className="text-xs text-muted-foreground">Concluídas</p>
            <p className="text-sm font-bold text-primary mt-1">{completed.length} de {goals.length}</p>
          </div>
          <div className="bg-card rounded-xl p-3 shadow-card text-center">
            <p className="text-xs text-muted-foreground">Mais próxima</p>
            <p className="text-sm font-bold text-primary mt-1">
              {nextGoal ? `${Math.round((nextGoal.currentAmount / nextGoal.targetAmount) * 100)}%` : '—'}
            </p>
          </div>
        </div>
      )}

      {/* Goals grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {inProgress.map(goal => {
          const pct = Math.min((goal.currentAmount / goal.targetAmount) * 100, 100);
          return (
            <div key={goal.id} className="bg-card rounded-2xl p-4 shadow-card space-y-3">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-secondary flex items-center justify-center shrink-0">
                  <GoalIcon name={goal.iconName} className="text-primary" />
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
                        <DropdownMenuItem onClick={() => openEdit(goal)} className="gap-2 cursor-pointer"><Pencil size={14} /> Editar</DropdownMenuItem>
                        <DropdownMenuItem onClick={() => setDeleteId(goal.id)} className="gap-2 cursor-pointer text-destructive focus:text-destructive"><Trash2 size={14} /> Apagar</DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                  <p className="text-xs text-muted-foreground mt-0.5">{fmt(goal.currentAmount)} de {fmt(goal.targetAmount)}</p>
                </div>
              </div>
              <div className="space-y-1">
                <Progress value={pct} className="h-2" />
                <div className="flex justify-between text-xs">
                  <span className="font-semibold text-primary">{pct.toFixed(0)}%</span>
                  <span className="text-muted-foreground">Faltam {fmt(Math.max(goal.targetAmount - goal.currentAmount, 0))}</span>
                </div>
              </div>
              <button onClick={() => { setInvestGoal(goal); setInvestAmount(''); setInvestError(''); }} className="w-full flex items-center justify-center gap-1.5 py-2 rounded-xl text-sm font-medium text-primary hover:bg-primary/5 transition-colors border border-border/50">
                <TrendingUp size={14} /> Investir nesta meta
              </button>
            </div>
          );
        })}

        {completed.map(goal => (
          <div key={goal.id} className="bg-card rounded-2xl p-4 shadow-card space-y-3 opacity-80">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-xl bg-income-soft flex items-center justify-center shrink-0">
                <GoalIcon name={goal.iconName} className="text-income" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between">
                  <h3 className="font-semibold text-sm truncate">{goal.name}</h3>
                  <span className="text-xs bg-income-soft text-income px-2 py-0.5 rounded-full font-medium flex items-center gap-1"><Check size={10} /> Concluída</span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">{fmt(goal.targetAmount)}</p>
              </div>
            </div>
            <Progress value={100} className="h-2" />
          </div>
        ))}
      </div>

      {/* Empty state */}
      {goals.length === 0 && (
        <div className="text-center py-16 space-y-3">
          <div className="w-16 h-16 rounded-2xl bg-secondary flex items-center justify-center mx-auto">
            <Target size={28} className="text-primary" />
          </div>
          <h3 className="font-semibold">Comece suas metas</h3>
          <p className="text-sm text-muted-foreground max-w-xs mx-auto">Defina objetivos financeiros e acompanhe seu progresso</p>
          <Button onClick={openCreate} className="gradient-primary text-primary-foreground mt-2"><Plus size={16} className="mr-2" /> Nova meta</Button>
        </div>
      )}

      {/* Create dialog */}
      <Dialog open={createOpen} onOpenChange={o => { if (!o) setCreateOpen(false); }}>
        <DialogContent className="max-w-sm rounded-2xl">
          <DialogHeader><DialogTitle>Nova Meta</DialogTitle></DialogHeader>
          <GoalFormFields />
          <Button onClick={handleCreate} disabled={createGoal.isPending} className="w-full gradient-primary text-primary-foreground mt-2">
            {createGoal.isPending ? 'Criando...' : 'Criar Meta'}
          </Button>
        </DialogContent>
      </Dialog>

      {/* Edit dialog */}
      <Dialog open={!!editGoal} onOpenChange={o => { if (!o) setEditGoal(null); }}>
        <DialogContent className="max-w-sm rounded-2xl">
          <DialogHeader><DialogTitle>Editar Meta</DialogTitle></DialogHeader>
          <GoalFormFields />
          <Button onClick={handleEdit} disabled={updateGoal.isPending} className="w-full gradient-primary text-primary-foreground mt-2">
            {updateGoal.isPending ? 'Salvando...' : 'Salvar Alterações'}
          </Button>
        </DialogContent>
      </Dialog>

      {/* Invest sheet */}
      <Sheet open={!!investGoal} onOpenChange={o => { if (!o) setInvestGoal(null); }}>
        <SheetContent side="bottom" className="rounded-t-2xl pb-8">
          <SheetHeader><SheetTitle>Investir em {investGoal?.name}</SheetTitle></SheetHeader>
          <div className="space-y-3 mt-4">
            <Input type="number" placeholder="Valor a investir (R$)" value={investAmount} onChange={e => { setInvestAmount(e.target.value); setInvestError(''); }} className={cn(investError && 'ring-2 ring-destructive')} />
            {investError && <p className="text-sm text-destructive">{investError}</p>}
            <Button onClick={handleInvest} disabled={addProgress.isPending} className="w-full gradient-primary text-primary-foreground">
              {addProgress.isPending ? 'Aportando...' : 'Confirmar aporte'}
            </Button>
          </div>
        </SheetContent>
      </Sheet>

      {/* Delete confirm */}
      <AlertDialog open={!!deleteId} onOpenChange={o => { if (!o) setDeleteId(null); }}>
        <AlertDialogContent className="max-w-xs rounded-2xl">
          <AlertDialogHeader>
            <AlertDialogTitle>Apagar meta?</AlertDialogTitle>
            <AlertDialogDescription>Todo o progresso será perdido. Essa ação não pode ser desfeita.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancelar</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">Apagar</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default Goals;
```

- [ ] **Step 2: Test in browser**

Start dev server. Navigate to `/goals`. Verify:
- Summary strip shows with correct data
- Goal cards render with Lucide icons (no emojis)
- "Investir" opens a bottom sheet
- Create/Edit dialog works
- Desktop view shows 2-column grid

- [ ] **Step 3: Commit**

```bash
git add src/pages/Goals.tsx
git commit -m "feat: rebuild Goals page with backend integration and Lucide icons"
```

---

## Task 15: Install @use-gesture/react

**Repo:** Frontend

- [ ] **Step 1: Install**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
npm install @use-gesture/react
```

- [ ] **Step 2: Commit**

```bash
git add package.json package-lock.json
git commit -m "feat: add @use-gesture/react for swipe interactions"
```

---

## Task 16: Transactions page redesign

**Repo:** Frontend

**Files:**
- Modify: `src/pages/Transactions.tsx`

- [ ] **Step 1: Rewrite Transactions.tsx**

```tsx
// src/pages/Transactions.tsx
import { useState, useMemo, useRef } from 'react';
import { useFinance, Transaction, expenseCategories } from '@/contexts/FinanceContext';
import { Search, UtensilsCrossed, Car, Home, Heart, Gamepad2, Shirt, BookOpen, Briefcase, Laptop, TrendingUp, Gift, MoreHorizontal } from 'lucide-react';
import { format, parseISO } from 'date-fns';
import { ptBR } from 'date-fns/locale';
import { cn } from '@/lib/utils';
import { useDrag } from '@use-gesture/react';
import { toast } from 'sonner';

const CATEGORY_ICONS: Record<string, React.ElementType> = {
  'Alimentação': UtensilsCrossed,
  'Transporte': Car,
  'Moradia': Home,
  'Saúde': Heart,
  'Lazer': Gamepad2,
  'Vestuário': Shirt,
  'Educação': BookOpen,
  'Salário': Briefcase,
  'Freelance': Laptop,
  'Investimento': TrendingUp,
  'Presente': Gift,
};

function CategoryIcon({ category, size = 16 }: { category: string; size?: number }) {
  const Icon = CATEGORY_ICONS[category] ?? MoreHorizontal;
  return <Icon size={size} />;
}

const TYPE_FILTERS = [
  { key: 'all', label: 'Todas' },
  { key: 'income', label: 'Entradas' },
  { key: 'expense', label: 'Saídas' },
] as const;

const ALL_CATEGORIES = [...expenseCategories, 'Salário', 'Freelance', 'Investimento', 'Presente'];

interface SwipeRowProps {
  transaction: Transaction;
  onDelete: (id: string) => void;
}

const SwipeRow = ({ transaction: t, onDelete }: SwipeRowProps) => {
  const [offset, setOffset] = useState(0);
  const DELETE_THRESHOLD = 80;

  const bind = useDrag(({ movement: [mx], last, cancel }) => {
    const clamped = Math.min(0, mx);
    setOffset(clamped);
    if (last) {
      if (clamped < -DELETE_THRESHOLD) {
        onDelete(t.id);
      } else {
        setOffset(0);
      }
    }
  }, { axis: 'x', filterTaps: true });

  const fmt = (v: number) => v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

  return (
    <div className="relative overflow-hidden rounded-xl">
      <div className="absolute inset-y-0 right-0 flex items-center justify-end px-4 bg-destructive rounded-xl">
        <span className="text-xs font-semibold text-destructive-foreground">Apagar</span>
      </div>
      <div
        {...bind()}
        style={{ transform: `translateX(${offset}px)`, touchAction: 'pan-y' }}
        className="bg-card p-3.5 flex items-center gap-3 relative z-10 cursor-grab active:cursor-grabbing select-none"
      >
        <div className={cn('w-9 h-9 rounded-lg flex items-center justify-center shrink-0', t.type === 'income' ? 'bg-income-soft text-income' : 'bg-expense-soft text-expense')}>
          <CategoryIcon category={t.category} size={16} />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium truncate">{t.description}</p>
          <p className="text-xs text-muted-foreground">{t.category}</p>
        </div>
        <p className={cn('text-sm font-semibold shrink-0', t.type === 'income' ? 'text-income' : 'text-expense')}>
          {t.type === 'income' ? '+' : '-'}{fmt(t.amount)}
        </p>
      </div>
    </div>
  );
};

const Transactions = () => {
  const { transactions, deleteTransaction } = useFinance();
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<'all' | 'income' | 'expense'>('all');
  const [categoryFilter, setCategoryFilter] = useState<string | null>(null);

  const filtered = useMemo(() => {
    return transactions.filter(t => {
      if (typeFilter !== 'all' && t.type !== typeFilter) return false;
      if (categoryFilter && t.category !== categoryFilter) return false;
      if (search && !t.description.toLowerCase().includes(search.toLowerCase()) && !t.category.toLowerCase().includes(search.toLowerCase())) return false;
      return true;
    });
  }, [transactions, search, typeFilter, categoryFilter]);

  const grouped = useMemo(() => {
    const map: Record<string, Transaction[]> = {};
    filtered.forEach(t => { (map[t.date] = map[t.date] || []).push(t); });
    return Object.entries(map).sort(([a], [b]) => b.localeCompare(a));
  }, [filtered]);

  const handleDelete = (id: string) => {
    const t = transactions.find(x => x.id === id);
    deleteTransaction(id);
    toast('Transação apagada', {
      action: {
        label: 'Desfazer',
        onClick: () => { /* optimistic undo not implemented — refresh triggers re-fetch */ window.location.reload(); },
      },
    });
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Transações</h1>

      {/* Search */}
      <div className="relative">
        <Search size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted-foreground" />
        <input
          type="text"
          placeholder="Buscar transação..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="w-full bg-card rounded-xl pl-10 pr-4 py-3 text-sm shadow-card border-0 outline-none focus:ring-2 focus:ring-primary/30 placeholder:text-muted-foreground"
        />
      </div>

      {/* Type filter chips */}
      <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-hide">
        {TYPE_FILTERS.map(({ key, label }) => (
          <button
            key={key}
            onClick={() => setTypeFilter(key)}
            className={cn('shrink-0 px-3 py-1.5 rounded-full text-xs font-semibold transition-colors', typeFilter === key ? 'gradient-primary text-primary-foreground' : 'bg-card text-muted-foreground shadow-card')}
          >
            {label}
          </button>
        ))}
        <div className="w-px bg-border mx-1 shrink-0" />
        {ALL_CATEGORIES.map(cat => (
          <button
            key={cat}
            onClick={() => setCategoryFilter(categoryFilter === cat ? null : cat)}
            className={cn('shrink-0 px-3 py-1.5 rounded-full text-xs font-semibold transition-colors', categoryFilter === cat ? 'bg-secondary text-primary' : 'bg-card text-muted-foreground shadow-card')}
          >
            {cat}
          </button>
        ))}
      </div>

      {/* Transaction list */}
      <div className="space-y-4">
        {grouped.map(([date, items]) => (
          <div key={date}>
            <p className="text-xs font-semibold text-muted-foreground/70 uppercase tracking-wide mb-2">
              {format(parseISO(date), "dd 'de' MMMM", { locale: ptBR })}
            </p>
            <div className="space-y-2">
              {items.map(t => (
                <SwipeRow key={t.id} transaction={t} onDelete={handleDelete} />
              ))}
            </div>
          </div>
        ))}
        {grouped.length === 0 && (
          <div className="text-center py-16 space-y-2">
            <div className="w-14 h-14 rounded-2xl bg-secondary flex items-center justify-center mx-auto">
              <Search size={22} className="text-primary" />
            </div>
            <p className="text-muted-foreground text-sm">Nenhuma transação encontrada</p>
          </div>
        )}
      </div>
    </div>
  );
};

export default Transactions;
```

- [ ] **Step 2: Test in browser**

Navigate to `/transactions`. Verify:
- Search bar always visible
- Filter chips horizontal, scrollable
- Swipe a transaction left on mobile — delete appears and triggers on full swipe
- Empty state shows Lucide icon (no emoji)

- [ ] **Step 3: Commit**

```bash
git add src/pages/Transactions.tsx
git commit -m "feat: redesign Transactions with always-visible filters and swipe-to-delete"
```

---

## Task 17: AddTransaction page redesign

**Repo:** Frontend

**Files:**
- Modify: `src/pages/AddTransaction.tsx`

- [ ] **Step 1: Rewrite AddTransaction.tsx**

```tsx
// src/pages/AddTransaction.tsx
import { useState } from 'react';
import {
  UtensilsCrossed, Car, Home, Heart, Gamepad2, Shirt, BookOpen, MoreHorizontal,
  Briefcase, Laptop, TrendingUp, Gift, Check,
} from 'lucide-react';
import { useFinance, expenseCategories, incomeCategories, TransactionType } from '@/contexts/FinanceContext';
import { getApiErrorMessage } from '@/lib/api-error';
import { toastError } from '@/lib/toast';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';

const EXPENSE_ICONS: Record<string, React.ElementType> = {
  'Alimentação': UtensilsCrossed,
  'Transporte': Car,
  'Moradia': Home,
  'Saúde': Heart,
  'Lazer': Gamepad2,
  'Vestuário': Shirt,
  'Educação': BookOpen,
  'Assinaturas': MoreHorizontal,
  'Viagens': MoreHorizontal,
  'Outros': MoreHorizontal,
};

const INCOME_ICONS: Record<string, React.ElementType> = {
  'Salário': Briefcase,
  'Freelance': Laptop,
  'Investimento': TrendingUp,
  'Presente': Gift,
  'Outros': MoreHorizontal,
};

const AddTransaction = () => {
  const { addTransaction } = useFinance();
  const [type, setType] = useState<TransactionType>('expense');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState(expenseCategories[0]);
  const [date, setDate] = useState(format(new Date(), 'yyyy-MM-dd'));
  const [note, setNote] = useState('');
  const [saved, setSaved] = useState(false);
  const [amountError, setAmountError] = useState('');
  const [descriptionError, setDescriptionError] = useState('');

  const cats = type === 'expense' ? expenseCategories : incomeCategories;
  const icons = type === 'expense' ? EXPENSE_ICONS : INCOME_ICONS;

  const handleTypeChange = (t: TransactionType) => {
    setType(t);
    const newCats = t === 'expense' ? expenseCategories : incomeCategories;
    setCategory(newCats[0]);
  };

  const handleSave = async () => {
    setAmountError('');
    setDescriptionError('');
    let hasError = false;
    if (!amount || parseFloat(amount) <= 0) { setAmountError('Informe um valor válido'); hasError = true; }
    if (!description.trim()) { setDescriptionError('Informe uma descrição'); hasError = true; }
    if (hasError) return;
    try {
      await addTransaction({ type, amount: parseFloat(amount), description, category, date, note: note || undefined });
      setSaved(true);
      setTimeout(() => { setSaved(false); setAmount(''); setDescription(''); setNote(''); }, 1500);
    } catch (err) { toastError(getApiErrorMessage(err)); }
  };

  const isIncome = type === 'income';

  return (
    <div className="max-w-md mx-auto space-y-5">
      <h1 className="text-2xl font-bold">Nova movimentação</h1>

      {/* Type toggle */}
      <div className="flex bg-card rounded-xl p-1 shadow-card">
        {(['expense', 'income'] as const).map(t => (
          <button
            key={t}
            onClick={() => handleTypeChange(t)}
            className={cn('flex-1 py-2.5 rounded-lg text-sm font-semibold transition-all', type === t ? (t === 'income' ? 'bg-income-soft text-income' : 'bg-expense-soft text-expense') : 'text-muted-foreground')}
          >
            {t === 'income' ? 'Entrada' : 'Saída'}
          </button>
        ))}
      </div>

      {/* Amount — colour changes with type */}
      <div>
        <div className={cn('rounded-2xl p-5 text-center transition-colors', isIncome ? 'bg-income-soft' : 'bg-expense-soft')}>
          <p className={cn('text-xs font-medium mb-2', isIncome ? 'text-income' : 'text-expense')}>
            Valor da {isIncome ? 'entrada' : 'saída'}
          </p>
          <div className="relative flex items-center justify-center gap-2">
            <span className={cn('text-xl font-bold', isIncome ? 'text-income' : 'text-expense')}>R$</span>
            <input
              type="number"
              inputMode="decimal"
              value={amount}
              onChange={e => { setAmount(e.target.value); setAmountError(''); }}
              placeholder="0,00"
              className={cn('bg-transparent text-3xl font-bold w-40 outline-none text-center placeholder:opacity-40', isIncome ? 'text-income' : 'text-expense', amountError && 'placeholder:text-destructive')}
            />
          </div>
          {amountError && <p className="text-sm text-destructive mt-1">{amountError}</p>}
        </div>
      </div>

      {/* Description */}
      <div>
        <label className="text-xs font-medium text-muted-foreground mb-1.5 block">Descrição</label>
        <input
          type="text"
          value={description}
          onChange={e => { setDescription(e.target.value); setDescriptionError(''); }}
          placeholder="Ex: Supermercado"
          className={cn('w-full bg-card rounded-xl px-4 py-3 text-sm shadow-card border-0 outline-none focus:ring-2 focus:ring-primary/30 placeholder:text-muted-foreground/40', descriptionError && 'ring-2 ring-destructive')}
        />
        {descriptionError && <p className="text-sm text-destructive mt-1">{descriptionError}</p>}
      </div>

      {/* Category */}
      <div>
        <label className="text-xs font-medium text-muted-foreground mb-1.5 block">Categoria</label>
        <div className="grid grid-cols-5 gap-2">
          {cats.map(c => {
            const Icon = icons[c] ?? MoreHorizontal;
            return (
              <button
                key={c}
                onClick={() => setCategory(c)}
                className={cn('flex flex-col items-center gap-1 py-2.5 rounded-xl text-[10px] font-medium transition-all', category === c ? 'gradient-primary text-primary-foreground shadow-card' : 'bg-card text-muted-foreground shadow-card')}
              >
                <Icon size={16} />
                <span className="truncate w-full text-center px-0.5 leading-tight">{c}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Date */}
      <div>
        <label className="text-xs font-medium text-muted-foreground mb-1.5 block">Data</label>
        <input type="date" value={date} onChange={e => setDate(e.target.value)} className="w-full bg-card rounded-xl px-4 py-3 text-sm shadow-card border-0 outline-none focus:ring-2 focus:ring-primary/30" />
      </div>

      {/* Note */}
      <div>
        <label className="text-xs font-medium text-muted-foreground mb-1.5 block">Observação (opcional)</label>
        <textarea value={note} onChange={e => setNote(e.target.value)} placeholder="Algum detalhe extra..." rows={2} className="w-full bg-card rounded-xl px-4 py-3 text-sm shadow-card border-0 outline-none focus:ring-2 focus:ring-primary/30 resize-none placeholder:text-muted-foreground/40" />
      </div>

      {/* Save */}
      <button
        onClick={handleSave}
        disabled={saved}
        className={cn('w-full py-4 rounded-xl text-base font-semibold transition-all', saved ? 'bg-income-soft text-income' : 'gradient-primary text-primary-foreground shadow-card active:scale-[0.98]')}
      >
        {saved ? <span className="flex items-center justify-center gap-2"><Check size={20} /> Registrado</span> : 'Salvar movimentação'}
      </button>
    </div>
  );
};

export default AddTransaction;
```

- [ ] **Step 2: Test in browser**

Navigate to `/add`. Verify:
- Toggle Entrada/Saída changes amount field colour
- Entrada shows income categories (Salário, Freelance…) with Lucide icons
- Saída shows expense categories
- No emojis anywhere

- [ ] **Step 3: Commit**

```bash
git add src/pages/AddTransaction.tsx
git commit -m "feat: redesign AddTransaction with income categories and dynamic colour"
```

---

## Task 18: Dashboard responsive grid

**Repo:** Frontend

**Files:**
- Modify: `src/pages/Dashboard.tsx`

- [ ] **Step 1: Rewrite Dashboard.tsx**

```tsx
// src/pages/Dashboard.tsx
import piggyLogo from '@/assets/piggy-logo.png';
import { useFinance } from '@/contexts/FinanceContext';
import { useNavigate } from 'react-router-dom';
import { ArrowDownLeft, ArrowUpRight } from 'lucide-react';
import {
  UtensilsCrossed, Car, Home, Heart, Gamepad2, Shirt, BookOpen,
  Briefcase, Laptop, TrendingUp, Gift, MoreHorizontal,
} from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, ResponsiveContainer, PieChart, Pie, Cell, Tooltip } from 'recharts';
import { useMemo } from 'react';
import { format, parseISO, startOfWeek, addDays } from 'date-fns';
import { ptBR } from 'date-fns/locale';

const CATEGORY_ICONS: Record<string, React.ElementType> = {
  'Alimentação': UtensilsCrossed, 'Transporte': Car, 'Moradia': Home,
  'Saúde': Heart, 'Lazer': Gamepad2, 'Vestuário': Shirt, 'Educação': BookOpen,
  'Salário': Briefcase, 'Freelance': Laptop, 'Investimento': TrendingUp, 'Presente': Gift,
};
function CategoryIcon({ category, size = 16 }: { category: string; size?: number }) {
  const Icon = CATEGORY_ICONS[category] ?? MoreHorizontal;
  return <Icon size={size} />;
}

const PIE_COLORS = ['#642a91', '#2f2b8e', '#4444d3', '#b365ea', 'hsl(155,55%,42%)', 'hsl(0,65%,55%)'];

const Dashboard = () => {
  const { userName, transactions, totalIncome, totalExpense, balance } = useFinance();
  const navigate = useNavigate();

  const fmt = (v: number) => v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

  const weeklyData = useMemo(() => {
    const days = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'];
    const start = startOfWeek(new Date());
    return days.map((name, i) => {
      const day = format(addDays(start, i), 'yyyy-MM-dd');
      const total = transactions.filter(t => t.type === 'expense' && t.date === day).reduce((s, t) => s + t.amount, 0);
      return { name, total };
    });
  }, [transactions]);

  const categoryData = useMemo(() => {
    const map: Record<string, number> = {};
    transactions.filter(t => t.type === 'expense').forEach(t => { map[t.category] = (map[t.category] || 0) + t.amount; });
    return Object.entries(map).map(([name, value]) => ({ name, value }));
  }, [transactions]);

  const recentTransactions = transactions.slice(0, 5);

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Olá, {userName}</h1>
          <p className="text-sm text-muted-foreground mt-0.5">Veja como está seu dinheiro hoje</p>
        </div>
        <button onClick={() => navigate('/profile')} className="w-11 h-11 rounded-full bg-secondary flex items-center justify-center overflow-hidden">
          <img src={piggyLogo} alt="PiggyFinance" className="w-9 h-9 object-contain" />
        </button>
      </div>

      {/* Balance card — full width */}
      <div className="rounded-2xl p-5 text-white shadow-card" style={{ backgroundColor: '#642a91' }}>
        <p className="text-sm opacity-80">Saldo atual</p>
        <p className="text-3xl font-bold mt-1">{fmt(balance)}</p>
        <div className="flex gap-4 mt-4">
          <div className="flex-1 bg-white/15 rounded-xl p-3">
            <div className="flex items-center gap-1.5 text-xs opacity-80"><ArrowDownLeft size={14} /> Entradas</div>
            <p className="text-lg font-semibold mt-1">{fmt(totalIncome)}</p>
          </div>
          <div className="flex-1 bg-white/15 rounded-xl p-3">
            <div className="flex items-center gap-1.5 text-xs opacity-80"><ArrowUpRight size={14} /> Saídas</div>
            <p className="text-lg font-semibold mt-1">{fmt(totalExpense)}</p>
          </div>
        </div>
      </div>

      {/* Responsive grid: 1 col mobile, 2 col desktop */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
        {/* Recent transactions */}
        <section>
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-lg font-semibold">Últimas movimentações</h2>
            <button onClick={() => navigate('/transactions')} className="text-xs text-primary font-medium">Ver todas</button>
          </div>
          <div className="space-y-2">
            {recentTransactions.map(t => (
              <div key={t.id} className="bg-card rounded-xl p-3.5 shadow-card flex items-center gap-3">
                <div className={`w-9 h-9 rounded-lg flex items-center justify-center shrink-0 ${t.type === 'income' ? 'bg-income-soft text-income' : 'bg-expense-soft text-expense'}`}>
                  <CategoryIcon category={t.category} size={16} />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium truncate">{t.description}</p>
                  <p className="text-xs text-muted-foreground">{format(parseISO(t.date), "dd 'de' MMM", { locale: ptBR })}</p>
                </div>
                <p className={`text-sm font-semibold shrink-0 ${t.type === 'income' ? 'text-income' : 'text-expense'}`}>
                  {t.type === 'income' ? '+' : '-'}{fmt(t.amount)}
                </p>
              </div>
            ))}
            {recentTransactions.length === 0 && (
              <p className="text-sm text-muted-foreground text-center py-6">Nenhuma transação ainda</p>
            )}
          </div>
        </section>

        {/* Charts */}
        <section className="space-y-4">
          <div className="bg-card rounded-xl p-4 shadow-card">
            <p className="text-sm text-muted-foreground mb-3">Gastos da semana</p>
            <ResponsiveContainer width="100%" height={130}>
              <BarChart data={weeklyData}>
                <XAxis dataKey="name" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'hsl(250,10%,50%)' }} />
                <YAxis hide />
                <Tooltip formatter={(v: number) => fmt(v)} contentStyle={{ borderRadius: 12, border: 'none', boxShadow: '0 4px 12px rgba(0,0,0,0.08)' }} />
                <Bar dataKey="total" fill="#642a91" radius={[6, 6, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
          {categoryData.length > 0 && (
            <div className="bg-card rounded-xl p-4 shadow-card">
              <p className="text-sm text-muted-foreground mb-3">Gastos por categoria</p>
              <div className="flex items-center gap-4">
                <ResponsiveContainer width={100} height={100}>
                  <PieChart>
                    <Pie data={categoryData} dataKey="value" cx="50%" cy="50%" innerRadius={28} outerRadius={46} strokeWidth={0}>
                      {categoryData.map((_, i) => <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />)}
                    </Pie>
                    <Tooltip formatter={(v: number) => fmt(v)} />
                  </PieChart>
                </ResponsiveContainer>
                <div className="flex-1 space-y-1.5">
                  {categoryData.slice(0, 4).map((d, i) => (
                    <div key={d.name} className="flex items-center justify-between text-xs">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: PIE_COLORS[i % PIE_COLORS.length] }} />
                        <span className="text-muted-foreground truncate">{d.name}</span>
                      </div>
                      <span className="font-semibold ml-2 shrink-0">{fmt(d.value)}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
          <button onClick={() => navigate('/charts')} className="w-full bg-secondary/60 hover:bg-secondary text-primary font-medium text-sm flex items-center justify-center gap-1.5 py-2.5 rounded-xl border border-border/50 transition-colors">
            Ver todos os gráficos
          </button>
        </section>
      </div>
    </div>
  );
};

export default Dashboard;
```

- [ ] **Step 2: Test in browser**

Navigate to `/`. Verify:
- Mobile: single column, balance → transactions → charts
- Desktop (≥768px): balance full width, then 2-column grid
- No pig mascot animation (removed intentionally — was using emoji in adjacent components)
- No emojis

- [ ] **Step 3: Commit**

```bash
git add src/pages/Dashboard.tsx
git commit -m "feat: redesign Dashboard with responsive 2-col grid"
```

---

## Task 19: Profile page redesign

**Repo:** Frontend

**Files:**
- Modify: `src/pages/Profile.tsx`

- [ ] **Step 1: Rewrite Profile.tsx**

```tsx
// src/pages/Profile.tsx
import piggyLogo from '@/assets/piggy-logo.png';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  MessageCircle, Settings2, Moon, Sun, LogOut, ChevronRight,
  Crown, Shield, Check, ExternalLink, Zap,
} from 'lucide-react';
import { clearAuthToken, getCurrentUser } from '@/services/api';
import WhatsAppLinkSheet from '@/components/WhatsAppLinkSheet';
import { Switch } from '@/components/ui/switch';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { useFinance } from '@/contexts/FinanceContext';

type PlanId = 'free' | 'plus' | 'pro';

const PLANS: { id: PlanId; name: string; price: string; features: string[]; popular?: boolean }[] = [
  { id: 'free', name: 'Gratuito', price: 'R$ 0', features: ['5 metas ativas', 'Relatórios básicos', 'Categorias padrão'] },
  { id: 'plus', name: 'Plus', price: 'R$ 9,90/mês', features: ['15 metas ativas', 'Relatórios avançados', 'Categorias ilimitadas', 'Exportar dados'], popular: true },
  { id: 'pro', name: 'Pro', price: 'R$ 19,90/mês', features: ['Metas ilimitadas', 'IA financeira', 'Suporte prioritário', 'Tudo do Plus'] },
];

const Profile = () => {
  const navigate = useNavigate();
  const { userName } = useFinance();
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'));
  const [currentPlan] = useState<PlanId>('free');
  const [whatsappLinked, setWhatsappLinked] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [plansOpen, setPlansOpen] = useState(false);

  useEffect(() => {
    getCurrentUser().then(u => setWhatsappLinked(u.whatsappLinked)).catch(() => {});
  }, []);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    localStorage.setItem('piggy-theme', dark ? 'dark' : 'light');
  }, [dark]);

  const isPaid = currentPlan !== 'free';
  const activePlan = PLANS.find(p => p.id === currentPlan)!;

  return (
    <div className="max-w-md mx-auto space-y-5">
      {/* Avatar + info */}
      <div className="flex flex-col items-center gap-3 pt-4 pb-2">
        <div className="w-16 h-16 rounded-full bg-secondary border-2 border-primary flex items-center justify-center overflow-hidden">
          <img src={piggyLogo} alt="Avatar" className="w-12 h-12 object-contain" />
        </div>
        <div className="text-center">
          <h1 className="text-xl font-bold">{userName}</h1>
          <p className="text-xs text-muted-foreground">Membro desde Fev 2026</p>
          <span className={`inline-block text-[10px] font-bold px-2.5 py-1 rounded-full uppercase tracking-wide mt-1.5 ${isPaid ? 'bg-primary/15 text-primary' : 'bg-secondary text-secondary-foreground'}`}>
            {activePlan.name}
          </span>
        </div>
      </div>

      {/* Upgrade / manage subscription */}
      {!isPaid ? (
        <button onClick={() => setPlansOpen(true)} className="w-full rounded-2xl p-4 text-white flex items-center gap-3 shadow-card" style={{ backgroundColor: '#642a91' }}>
          <Crown size={20} className="shrink-0" />
          <div className="flex-1 text-left">
            <p className="text-sm font-bold">Upgrade para Plus</p>
            <p className="text-xs opacity-75">Metas ilimitadas e exportação de dados</p>
          </div>
          <span className="text-xs font-bold bg-white/20 rounded-lg px-2 py-1 shrink-0">R$ 9,90/mês</span>
        </button>
      ) : (
        <button className="w-full bg-card rounded-2xl p-4 shadow-card flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-secondary flex items-center justify-center"><Shield size={18} className="text-primary" /></div>
          <div className="flex-1 text-left">
            <p className="text-sm font-medium">Plano {activePlan.name}</p>
            <p className="text-xs text-muted-foreground">Gerenciar assinatura</p>
          </div>
          <ExternalLink size={16} className="text-muted-foreground" />
        </button>
      )}

      {/* Menu */}
      <div className="space-y-2">
        <button onClick={() => setSheetOpen(true)} className={`w-full bg-card rounded-xl p-4 shadow-card flex items-center gap-3 text-left ${whatsappLinked ? 'border border-income/30' : ''}`}>
          <div className={`w-9 h-9 rounded-lg flex items-center justify-center ${whatsappLinked ? 'bg-income-soft text-income' : 'bg-secondary text-primary'}`}>
            {whatsappLinked ? <Check size={18} /> : <MessageCircle size={18} />}
          </div>
          <div className="flex-1">
            <p className="text-sm font-medium">{whatsappLinked ? 'WhatsApp vinculado' : 'Vincular WhatsApp'}</p>
            <p className="text-xs text-muted-foreground">{whatsappLinked ? 'Conta conectada' : 'Registre gastos pelo WhatsApp'}</p>
          </div>
          <ChevronRight size={16} className="text-muted-foreground" />
        </button>

        <div className="w-full bg-card rounded-xl p-4 shadow-card flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-secondary flex items-center justify-center text-primary">
            {dark ? <Sun size={18} /> : <Moon size={18} />}
          </div>
          <div className="flex-1">
            <p className="text-sm font-medium">{dark ? 'Modo claro' : 'Modo escuro'}</p>
            <p className="text-xs text-muted-foreground">Alterar aparência</p>
          </div>
          <Switch checked={dark} onCheckedChange={setDark} />
        </div>

        <button className="w-full bg-card rounded-xl p-4 shadow-card flex items-center gap-3 text-left">
          <div className="w-9 h-9 rounded-lg bg-secondary flex items-center justify-center text-primary"><Settings2 size={18} /></div>
          <div className="flex-1">
            <p className="text-sm font-medium">Preferências</p>
            <p className="text-xs text-muted-foreground">Ajustes do app</p>
          </div>
          <ChevronRight size={16} className="text-muted-foreground" />
        </button>

        <button onClick={() => { clearAuthToken(); navigate('/welcome'); }} className="w-full bg-card rounded-xl p-4 shadow-card flex items-center gap-3 text-left mt-4">
          <div className="w-9 h-9 rounded-lg bg-expense-soft flex items-center justify-center text-expense"><LogOut size={18} /></div>
          <p className="text-sm font-medium text-expense">Sair da conta</p>
        </button>
      </div>

      {/* WhatsApp sheet */}
      <WhatsAppLinkSheet open={sheetOpen} onClose={() => setSheetOpen(false)} alreadyLinked={whatsappLinked} />

      {/* Plans sheet */}
      <Sheet open={plansOpen} onOpenChange={setPlansOpen}>
        <SheetContent side="bottom" className="rounded-t-2xl pb-8 max-h-[80vh] overflow-y-auto">
          <SheetHeader><SheetTitle>Planos disponíveis</SheetTitle></SheetHeader>
          <div className="space-y-3 mt-4">
            {PLANS.map(plan => (
              <div key={plan.id} className={`bg-card rounded-2xl p-4 shadow-card border-2 ${currentPlan === plan.id ? 'border-primary' : 'border-transparent'}`}>
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <h3 className="text-sm font-bold">{plan.name}</h3>
                    {plan.popular && <span className="text-[9px] font-bold bg-primary/15 text-primary px-2 py-0.5 rounded-full uppercase">Popular</span>}
                  </div>
                  <p className="text-sm font-bold text-primary">{plan.price}</p>
                </div>
                <div className="space-y-1">
                  {plan.features.map(f => (
                    <div key={f} className="flex items-center gap-2 text-xs text-muted-foreground"><Check size={12} className="text-primary shrink-0" />{f}</div>
                  ))}
                </div>
                {currentPlan === plan.id ? (
                  <p className="text-xs font-medium text-primary text-center mt-3 py-1">Seu plano atual</p>
                ) : plan.id !== 'free' ? (
                  <button className="w-full mt-3 gradient-primary text-primary-foreground rounded-xl py-2.5 text-xs font-semibold">Assinar {plan.name}</button>
                ) : null}
              </div>
            ))}
          </div>
        </SheetContent>
      </Sheet>
    </div>
  );
};

export default Profile;
```

- [ ] **Step 2: Test in browser**

Navigate to `/profile`. Verify:
- Avatar centered at top
- Theme toggle is an inline Switch (no page navigation needed)
- Upgrade card is compact purple (free user)
- Plans sheet opens on tap
- No emojis

- [ ] **Step 3: Commit**

```bash
git add src/pages/Profile.tsx
git commit -m "feat: redesign Profile with centered avatar, inline toggle, and plans sheet"
```

---

## Task 20: Charts page cleanup

**Repo:** Frontend

**Files:**
- Modify: `src/pages/Charts.tsx`

- [ ] **Step 1: Remove `max-w-md` constraint** — open `src/pages/Charts.tsx` and find/replace all occurrences of `max-w-md mx-auto` (if present in the page root `div`) with nothing (the AppLayout handles this). Also add always-visible period filter chips.

Find the top of the return statement and wrap the existing content:

```tsx
// Replace the outer container div if it has max-w-md:
// FROM: <div className="pb-24 px-5 pt-6 max-w-md mx-auto">
// TO:   <div className="space-y-5">
```

Add filter chips after the page title:

```tsx
// Add after the <h1> or equivalent title element:
const PERIODS = [
  { label: 'Esta semana', value: 'week' },
  { label: 'Este mês', value: 'month' },
  { label: '3 meses', value: '3months' },
] as const;

const [period, setPeriod] = useState<'week' | 'month' | '3months'>('week');
```

```tsx
{/* Period chips — add after h1 */}
<div className="flex gap-2 overflow-x-auto pb-1">
  {PERIODS.map(({ label, value }) => (
    <button
      key={value}
      onClick={() => setPeriod(value)}
      className={cn('shrink-0 px-3 py-1.5 rounded-full text-xs font-semibold transition-colors', period === value ? 'gradient-primary text-primary-foreground' : 'bg-card text-muted-foreground shadow-card')}
    >
      {label}
    </button>
  ))}
</div>
```

- [ ] **Step 2: Test in browser**

Navigate to `/charts`. Verify:
- Page fills available width
- Period chips are visible without needing to toggle anything

- [ ] **Step 3: Commit**

```bash
git add src/pages/Charts.tsx
git commit -m "feat: remove max-w-md from Charts and add always-visible period filter"
```

---

## Task 21: Auth pages polish + global emoji removal

**Repo:** Frontend

**Files:**
- Modify: `src/pages/Welcome.tsx`
- Modify: `src/pages/Login.tsx`
- Modify: `src/pages/Register.tsx`
- Delete: `src/components/CategoryIcon.tsx`

- [ ] **Step 1: Scan for any remaining emojis in auth pages**

```bash
grep -rn '[^\x00-\x7F]' src/pages/Welcome.tsx src/pages/Login.tsx src/pages/Register.tsx
```

Remove any emoji found. Replace with empty string or a Lucide icon.

- [ ] **Step 2: Scan entire src for remaining emojis**

```bash
grep -rn '[^\x00-\x7F]' src/ --include="*.tsx" --include="*.ts" | grep -v node_modules
```

Fix every match — either remove or replace with the appropriate Lucide icon.

- [ ] **Step 3: Delete CategoryIcon**

```bash
rm src/components/CategoryIcon.tsx
```

Verify no remaining imports:

```bash
grep -rn "CategoryIcon" src/
```

Expected: no output.

- [ ] **Step 4: TypeScript check**

```bash
npm run build
```

Expected: no TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove CategoryIcon component and all remaining emojis"
```

---

## Task 22: Final integration test

**Repo:** Frontend + Backend

- [ ] **Step 1: Run backend tests**

```bash
cd /Users/gabrielbraga/Documents/Projects/java/PiggyFinance
./gradlew test
```
Expected: all tests pass.

- [ ] **Step 2: Run frontend dev server**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
npm run dev
```

- [ ] **Step 3: Smoke test each route**

Test the following manually:
- `/welcome` — loads, no emojis, buttons work
- `/login` — form submits, redirects to dashboard
- `/` (Dashboard) — balance loads, 2-column on desktop, 1-column on mobile
- `/transactions` — filter chips visible, swipe-to-delete works on mobile
- `/add` — type toggle changes colour, income categories show for Entrada
- `/charts` — fills width, period chips visible
- `/goals` — goals load from backend, invest sheet works, create/edit/delete work
- `/profile` — avatar centered, theme toggle inline, upgrade card shows, plans sheet opens
- All pages — BottomNav pill active on current page, FAB centre button works

- [ ] **Step 4: Verify no max-w-md on page roots**

```bash
grep -rn "max-w-md mx-auto" src/pages/ src/App.tsx
```

Expected: only `AddTransaction.tsx` and `Profile.tsx` retain it as an inner container (intentional for forms). No page root `div` or `App.tsx` should match.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat: complete frontend refactor — responsive layout, BottomNav pill, Goals backend, no emojis"
```
