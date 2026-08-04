# Gerar Relatórios (PDF) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `GET /api/v1/reports/pdf` endpoint that exports a PDF financial report (summary, category breakdown, transaction list, goal progress) for a date range, gated to Essencial+ subscribers.

**Architecture:** A new `ReportServiceImpl` aggregates data from existing repositories (`TransactionRepository`, `GoalRepository`) plus one new aggregate query, builds a `ReportData` record, and hands it to a small `ReportPdfGenerator` component that renders a Thymeleaf HTML template and converts it to PDF bytes via openhtmltopdf. A new `ReportController` exposes this as a byte-stream download. No new persistence — every call generates the PDF on demand.

**Tech Stack:** Spring Boot 4 (Java 21), Spring Data JPA, Thymeleaf (`spring-boot-starter-thymeleaf`), openhtmltopdf (`com.openhtmltopdf:openhtmltopdf-pdfbox`), JUnit 5 + Mockito + AssertJ (existing test stack).

## Global Constraints

- Content language: **PT-BR** (labels, currency as `R$ 1.234,56`, dates as `dd/MM/yyyy`).
- Access gate: `entitlementService.requireTier(userId, SubscriptionTier.ESSENCIAL)` — Free tier is rejected.
- File format: **PDF only** (no CSV/XLSX in this MVP).
- No persistence: nothing is written to the database or disk; the PDF is generated and returned in the same request.
- Date range: optional `startDate`/`endDate` query params, default to current month (`LocalDate.now().withDayOfMonth(1)` → `LocalDate.now()`), matching `/transactions/summary`.
- `startDate > endDate` → `BusinessException`, which `GlobalExceptionHandler.handleBusiness()` maps to **422 Unprocessable Entity** (verified against the actual handler — not 400).
- Transaction list cap: **500 rows**; beyond that, truncate and flag it (`transactionsTruncated` + `totalTransactionCount`).
- Reuse existing patterns: `EntitlementService`/`FeatureLockedException` (already wired to `GlobalExceptionHandler`), `TransactionSpecification.byFilter`, `TransactionRepository.getSummary`, `GoalRepository.findByUserIdOrderByCreatedAtAsc`. No `SecurityConfig` change needed — `anyRequest().authenticated()` already covers new endpoints.
- Correction vs. the design spec: the openhtmltopdf Maven coordinate is `com.openhtmltopdf:openhtmltopdf-pdfbox` (groupId `com.openhtmltopdf`, not `io.github.openhtmltopdf` as written in the spec — verified against the library's actual Maven Central publication).

Reference: `docs/superpowers/specs/2026-08-03-gerar-relatorios-design.md`

---

### Task 1: Report DTOs

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/model/dto/CategoryTotal.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/dto/CategoryBreakdownItem.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/dto/TransactionLineItem.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/dto/GoalProgressItem.java`
- Create: `src/main/java/com/piggy/piggyfinance/model/dto/ReportData.java`

**Interfaces:**
- Produces: `CategoryTotal(CategoryType category, BigDecimal total)` — raw JPQL projection, consumed by Task 2's query and Task 4's aggregation.
- Produces: `CategoryBreakdownItem(CategoryType category, BigDecimal total, BigDecimal percentage)`, `TransactionLineItem(LocalDate date, String description, CategoryType category, TransactionType type, BigDecimal amount)`, `GoalProgressItem(String name, BigDecimal currentAmount, BigDecimal targetAmount, BigDecimal percentage)` — consumed by `ReportData` and by Task 3's template/generator and Task 4's service.
- Produces: `ReportData(LocalDate startDate, LocalDate endDate, BigDecimal totalIncome, BigDecimal totalExpense, BigDecimal balance, List<CategoryBreakdownItem> categoryBreakdown, List<TransactionLineItem> transactions, boolean transactionsTruncated, long totalTransactionCount, List<GoalProgressItem> goals)` — consumed by `ReportPdfGenerator.render(String, ReportData)` (Task 3) and built by `ReportServiceImpl` (Task 4).

These are pure data holders (records), matching the existing `TransactionSummaryItem`/`TransactionSummaryResponse` convention in this codebase, neither of which has a dedicated test. No test is written for this task; correctness is verified by compilation and exercised by Task 3/4's tests.

- [ ] **Step 1: Create `CategoryTotal.java`**

```java
package com.piggy.piggyfinance.model.dto;

import com.piggy.piggyfinance.enums.CategoryType;

import java.math.BigDecimal;

public record CategoryTotal(CategoryType category, BigDecimal total) {}
```

- [ ] **Step 2: Create `CategoryBreakdownItem.java`**

```java
package com.piggy.piggyfinance.model.dto;

import com.piggy.piggyfinance.enums.CategoryType;

import java.math.BigDecimal;

public record CategoryBreakdownItem(CategoryType category, BigDecimal total, BigDecimal percentage) {}
```

- [ ] **Step 3: Create `TransactionLineItem.java`**

```java
package com.piggy.piggyfinance.model.dto;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionLineItem(
        LocalDate date,
        String description,
        CategoryType category,
        TransactionType type,
        BigDecimal amount
) {}
```

- [ ] **Step 4: Create `GoalProgressItem.java`**

```java
package com.piggy.piggyfinance.model.dto;

import java.math.BigDecimal;

public record GoalProgressItem(String name, BigDecimal currentAmount, BigDecimal targetAmount, BigDecimal percentage) {}
```

- [ ] **Step 5: Create `ReportData.java`**

```java
package com.piggy.piggyfinance.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReportData(
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        List<CategoryBreakdownItem> categoryBreakdown,
        List<TransactionLineItem> transactions,
        boolean transactionsTruncated,
        long totalTransactionCount,
        List<GoalProgressItem> goals
) {}
```

- [ ] **Step 6: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/model/dto/CategoryTotal.java \
        src/main/java/com/piggy/piggyfinance/model/dto/CategoryBreakdownItem.java \
        src/main/java/com/piggy/piggyfinance/model/dto/TransactionLineItem.java \
        src/main/java/com/piggy/piggyfinance/model/dto/GoalProgressItem.java \
        src/main/java/com/piggy/piggyfinance/model/dto/ReportData.java
git commit -m "feat: add report DTOs (ReportData and content records)"
```

---

### Task 2: Category-breakdown aggregate query

**Files:**
- Modify: `src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java`

**Interfaces:**
- Consumes: `CategoryTotal` from Task 1.
- Produces: `TransactionRepository.getExpenseByCategory(UUID userId, LocalDateTime start, LocalDateTime end): List<CategoryTotal>` — consumed by `ReportServiceImpl` in Task 4.

This mirrors the existing `getSummary()` query (same file), which also has no dedicated repository test — this codebase has no `@DataJpaTest` infrastructure at all. Its JPQL is validated the same way `getSummary()`'s is: Spring Data JPA parses `@Query` strings when building the repository proxy at application context startup. Correctness of how the service consumes it is covered by Task 4's `ReportServiceImplTest` with a mocked repository.

- [ ] **Step 1: Add the import for `CategoryTotal`**

In `src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java`, add this import alongside the existing `TransactionSummaryItem` import:

```java
import com.piggy.piggyfinance.model.dto.CategoryTotal;
```

- [ ] **Step 2: Add the `getExpenseByCategory` query**

Add this method to the `TransactionRepository` interface, right after `getSummary(...)`:

```java
    @Query("""
    select new com.piggy.piggyfinance.model.dto.CategoryTotal(
        t.category,
        sum(t.amount)
    )
    from Transaction t
    where t.user.id = :userId
      and t.type = com.piggy.piggyfinance.enums.TransactionType.EXPENSE
      and t.timestamp between :start and :end
    group by t.category
""")
    List<CategoryTotal> getExpenseByCategory(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/repository/TransactionRepository.java
git commit -m "feat: add expense-by-category aggregate query"
```

---

### Task 3: `ReportPdfGenerator` + Thymeleaf template

**Files:**
- Modify: `build.gradle`
- Create: `src/main/resources/templates/reports/financial-report.html`
- Create: `src/main/java/com/piggy/piggyfinance/service/impl/ReportPdfGenerator.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/impl/ReportPdfGeneratorTest.java`

**Interfaces:**
- Consumes: `ReportData` and its nested records from Task 1.
- Produces: `ReportPdfGenerator.render(String templateName, ReportData reportData): byte[]` — consumed by `ReportServiceImpl` in Task 4. Template name constant used: `"reports/financial-report"`.

This is the riskiest task (HTML→PDF conversion, Thymeleaf record property access) — it's tested end-to-end here with a real `TemplateEngine` and real template, so any incompatibility surfaces immediately rather than being masked by mocks later.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/piggy/piggyfinance/service/impl/ReportPdfGeneratorTest.java`:

```java
package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.model.dto.CategoryBreakdownItem;
import com.piggy.piggyfinance.model.dto.GoalProgressItem;
import com.piggy.piggyfinance.model.dto.ReportData;
import com.piggy.piggyfinance.model.dto.TransactionLineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPdfGeneratorTest {

    private ReportPdfGenerator generator;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        TemplateEngine templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        generator = new ReportPdfGenerator(templateEngine);
    }

    @Test
    void render_fullReportData_producesValidPdfBytes() {
        ReportData data = new ReportData(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                new BigDecimal("1000.00"),
                new BigDecimal("400.00"),
                new BigDecimal("600.00"),
                List.of(new CategoryBreakdownItem(CategoryType.FOOD, new BigDecimal("300.00"), new BigDecimal("75.00"))),
                List.of(new TransactionLineItem(LocalDate.of(2026, 8, 5), "Mercado", CategoryType.FOOD, TransactionType.EXPENSE, new BigDecimal("300.00"))),
                false,
                1,
                List.of(new GoalProgressItem("Viagem", new BigDecimal("500.00"), new BigDecimal("2000.00"), new BigDecimal("25.00")))
        );

        byte[] pdf = generator.render("reports/financial-report", data);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void render_emptyReportData_producesValidPdfBytes() {
        ReportData data = new ReportData(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                false,
                0,
                List.of()
        );

        byte[] pdf = generator.render("reports/financial-report", data);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.impl.ReportPdfGeneratorTest"`
Expected: FAIL — compile error, `ReportPdfGenerator` does not exist and `templates/reports/financial-report.html` is not resolvable.

- [ ] **Step 3: Add the Thymeleaf + openhtmltopdf dependencies**

In `build.gradle`, add these two lines to the `dependencies` block (next to the other `implementation` lines):

```gradle
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10'
```

- [ ] **Step 4: Create the Thymeleaf template**

Create `src/main/resources/templates/reports/financial-report.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"/>
    <title>Relatório Financeiro</title>
    <style>
        body { font-family: Helvetica, Arial, sans-serif; font-size: 11px; color: #1a1a1a; }
        h1 { font-size: 18px; margin-bottom: 4px; }
        h2 { font-size: 14px; margin-top: 20px; margin-bottom: 6px; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
        .period { color: #555; margin-bottom: 16px; }
        table { width: 100%; border-collapse: collapse; margin-bottom: 8px; }
        th, td { text-align: left; padding: 4px 6px; border-bottom: 1px solid #eee; }
        th { background-color: #f5f5f5; }
        .amount { text-align: right; }
        .empty { color: #888; font-style: italic; }
        .footer-note { color: #888; font-size: 10px; margin-top: 4px; }
    </style>
</head>
<body>
    <h1>Relatório Financeiro — PiggyFinance</h1>
    <p class="period" th:text="'Período: ' + ${#temporals.format(startDate, 'dd/MM/yyyy')} + ' a ' + ${#temporals.format(endDate, 'dd/MM/yyyy')}">Período</p>

    <h2>Resumo do período</h2>
    <table>
        <tr><th>Receitas</th><td class="amount" th:text="'R$ ' + ${#numbers.formatDecimal(totalIncome, 1, 'POINT', 2, 'COMMA')}">R$ 0,00</td></tr>
        <tr><th>Despesas</th><td class="amount" th:text="'R$ ' + ${#numbers.formatDecimal(totalExpense, 1, 'POINT', 2, 'COMMA')}">R$ 0,00</td></tr>
        <tr><th>Saldo</th><td class="amount" th:text="'R$ ' + ${#numbers.formatDecimal(balance, 1, 'POINT', 2, 'COMMA')}">R$ 0,00</td></tr>
    </table>

    <h2>Gastos por categoria</h2>
    <p class="empty" th:if="${#lists.isEmpty(categoryBreakdown)}">Nenhuma transação no período.</p>
    <table th:unless="${#lists.isEmpty(categoryBreakdown)}">
        <tr><th>Categoria</th><th class="amount">Total</th><th class="amount">%</th></tr>
        <tr th:each="item : ${categoryBreakdown}">
            <td th:text="${item.category}">Categoria</td>
            <td class="amount" th:text="'R$ ' + ${#numbers.formatDecimal(item.total, 1, 'POINT', 2, 'COMMA')}">R$ 0,00</td>
            <td class="amount" th:text="${#numbers.formatDecimal(item.percentage, 1, 'POINT', 1, 'COMMA')} + '%'">0%</td>
        </tr>
    </table>

    <h2>Extrato de transações</h2>
    <p class="empty" th:if="${#lists.isEmpty(transactions)}">Nenhuma transação no período.</p>
    <table th:unless="${#lists.isEmpty(transactions)}">
        <tr><th>Data</th><th>Descrição</th><th>Categoria</th><th>Tipo</th><th class="amount">Valor</th></tr>
        <tr th:each="tx : ${transactions}">
            <td th:text="${#temporals.format(tx.date, 'dd/MM/yyyy')}">01/01/2026</td>
            <td th:text="${tx.description}">Descrição</td>
            <td th:text="${tx.category}">Categoria</td>
            <td th:text="${tx.type}">Tipo</td>
            <td class="amount" th:text="'R$ ' + ${#numbers.formatDecimal(tx.amount, 1, 'POINT', 2, 'COMMA')}">R$ 0,00</td>
        </tr>
    </table>
    <p class="footer-note" th:if="${transactionsTruncated}"
       th:text="'Mostrando as primeiras ' + ${#lists.size(transactions)} + ' de ' + ${totalTransactionCount} + ' transações — refine o período para ver o extrato completo.'">Nota</p>

    <h2>Progresso de metas</h2>
    <p class="empty" th:if="${#lists.isEmpty(goals)}">Nenhuma meta cadastrada.</p>
    <table th:unless="${#lists.isEmpty(goals)}">
        <tr><th>Meta</th><th class="amount">Atual</th><th class="amount">Alvo</th><th class="amount">%</th></tr>
        <tr th:each="goal : ${goals}">
            <td th:text="${goal.name}">Meta</td>
            <td class="amount" th:text="'R$ ' + ${#numbers.formatDecimal(goal.currentAmount, 1, 'POINT', 2, 'COMMA')}">R$ 0,00</td>
            <td class="amount" th:text="'R$ ' + ${#numbers.formatDecimal(goal.targetAmount, 1, 'POINT', 2, 'COMMA')}">R$ 0,00</td>
            <td class="amount" th:text="${#numbers.formatDecimal(goal.percentage, 1, 'POINT', 1, 'COMMA')} + '%'">0%</td>
        </tr>
    </table>
</body>
</html>
```

- [ ] **Step 5: Create `ReportPdfGenerator.java`**

```java
package com.piggy.piggyfinance.service.impl;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.piggy.piggyfinance.model.dto.ReportData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ReportPdfGenerator {

    private final TemplateEngine templateEngine;

    public byte[] render(String templateName, ReportData reportData) {
        Context context = buildContext(reportData);
        String html = templateEngine.process(templateName, context);
        return convertToPdf(html);
    }

    private Context buildContext(ReportData data) {
        Context context = new Context();
        context.setVariable("startDate", data.startDate());
        context.setVariable("endDate", data.endDate());
        context.setVariable("totalIncome", data.totalIncome());
        context.setVariable("totalExpense", data.totalExpense());
        context.setVariable("balance", data.balance());
        context.setVariable("categoryBreakdown", data.categoryBreakdown());
        context.setVariable("transactions", data.transactions());
        context.setVariable("transactionsTruncated", data.transactionsTruncated());
        context.setVariable("totalTransactionCount", data.totalTransactionCount());
        context.setVariable("goals", data.goals());
        return context;
    }

    private byte[] convertToPdf(String html) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(outputStream);
            builder.run();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render report PDF", e);
        }
        return outputStream.toByteArray();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.impl.ReportPdfGeneratorTest"`
Expected: PASS (both tests). If it fails with a Thymeleaf property-resolution error on `item.category`/`tx.date`/`goal.name` etc. (a record-accessor OGNL issue), replace the offending `${x.y}` access in the template with the explicit accessor call `${x.y()}` and rerun.

- [ ] **Step 7: Commit**

```bash
git add build.gradle \
        src/main/resources/templates/reports/financial-report.html \
        src/main/java/com/piggy/piggyfinance/service/impl/ReportPdfGenerator.java \
        src/test/java/com/piggy/piggyfinance/service/impl/ReportPdfGeneratorTest.java
git commit -m "feat: add PDF rendering pipeline (Thymeleaf + openhtmltopdf)"
```

---

### Task 4: `ReportService` — tier gate, validation, aggregation

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/service/ReportService.java`
- Create: `src/main/java/com/piggy/piggyfinance/service/impl/ReportServiceImpl.java`
- Test: `src/test/java/com/piggy/piggyfinance/service/ReportServiceImplTest.java`

**Interfaces:**
- Consumes: `ReportPdfGenerator.render(String, ReportData)` (Task 3), `TransactionRepository.getSummary`/`getExpenseByCategory`/`findAll(Specification, Sort)` (existing + Task 2), `GoalRepository.findByUserIdOrderByCreatedAtAsc` (existing), `EntitlementService.requireTier` (existing), `TransactionSpecification.byFilter` (existing).
- Produces: `ReportService.generatePdf(UUID userId, LocalDate startDate, LocalDate endDate): byte[]` — consumed by `ReportController` in Task 5.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/piggy/piggyfinance/service/ReportServiceImplTest.java`:

```java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.dto.CategoryTotal;
import com.piggy.piggyfinance.model.dto.ReportData;
import com.piggy.piggyfinance.model.dto.TransactionSummaryItem;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.service.impl.ReportPdfGenerator;
import com.piggy.piggyfinance.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock TransactionRepository transactionRepository;
    @Mock GoalRepository goalRepository;
    @Mock EntitlementService entitlementService;
    @Mock ReportPdfGenerator pdfGenerator;
    @InjectMocks ReportServiceImpl service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @Test
    void generatePdf_freeTier_throwsFeatureLockedAndTouchesNothingElse() {
        doThrow(new FeatureLockedException("This feature requires the ESSENCIAL plan", SubscriptionTier.ESSENCIAL))
                .when(entitlementService).requireTier(userId, SubscriptionTier.ESSENCIAL);

        assertThatThrownBy(() -> service.generatePdf(userId, null, null))
                .isInstanceOf(FeatureLockedException.class);

        verifyNoInteractions(transactionRepository, goalRepository, pdfGenerator);
    }

    @Test
    void generatePdf_startAfterEnd_throwsBusinessException() {
        LocalDate start = LocalDate.of(2026, 8, 10);
        LocalDate end = LocalDate.of(2026, 8, 1);

        assertThatThrownBy(() -> service.generatePdf(userId, start, end))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(transactionRepository, goalRepository, pdfGenerator);
    }

    @Test
    void generatePdf_aggregatesSummaryCategoryTransactionsAndGoals() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);

        when(transactionRepository.getSummary(eq(userId), any(), any())).thenReturn(List.of(
                new TransactionSummaryItem(TransactionType.INCOME, new BigDecimal("1000.00")),
                new TransactionSummaryItem(TransactionType.EXPENSE, new BigDecimal("400.00"))
        ));
        when(transactionRepository.getExpenseByCategory(eq(userId), any(), any())).thenReturn(List.of(
                new CategoryTotal(CategoryType.FOOD, new BigDecimal("300.00")),
                new CategoryTotal(CategoryType.TRANSPORT, new BigDecimal("100.00"))
        ));

        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID()).description("Mercado").amount(new BigDecimal("300.00"))
                .type(TransactionType.EXPENSE).category(CategoryType.FOOD)
                .timestamp(LocalDateTime.of(2026, 8, 5, 10, 0)).build();
        when(transactionRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(tx));

        Goal goal = Goal.builder()
                .id(UUID.randomUUID()).name("Viagem")
                .targetAmount(new BigDecimal("2000.00")).currentAmount(new BigDecimal("500.00")).build();
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(goal));

        when(pdfGenerator.render(anyString(), any(ReportData.class))).thenReturn(new byte[]{1, 2, 3});

        byte[] result = service.generatePdf(userId, start, end);

        assertThat(result).isEqualTo(new byte[]{1, 2, 3});

        ArgumentCaptor<ReportData> captor = ArgumentCaptor.forClass(ReportData.class);
        verify(pdfGenerator).render(eq("reports/financial-report"), captor.capture());
        ReportData data = captor.getValue();

        assertThat(data.startDate()).isEqualTo(start);
        assertThat(data.endDate()).isEqualTo(end);
        assertThat(data.totalIncome()).isEqualByComparingTo("1000.00");
        assertThat(data.totalExpense()).isEqualByComparingTo("400.00");
        assertThat(data.balance()).isEqualByComparingTo("600.00");
        assertThat(data.categoryBreakdown()).hasSize(2);
        assertThat(data.categoryBreakdown().get(0).category()).isEqualTo(CategoryType.FOOD);
        assertThat(data.categoryBreakdown().get(0).percentage()).isEqualByComparingTo("75.00");
        assertThat(data.transactions()).hasSize(1);
        assertThat(data.transactionsTruncated()).isFalse();
        assertThat(data.totalTransactionCount()).isEqualTo(1);
        assertThat(data.goals()).hasSize(1);
        assertThat(data.goals().get(0).percentage()).isEqualByComparingTo("25.00");
    }

    @Test
    void generatePdf_emptyPeriod_buildsEmptyReportDataWithDefaultDatesAndNoError() {
        when(transactionRepository.getSummary(eq(userId), any(), any())).thenReturn(List.of());
        when(transactionRepository.getExpenseByCategory(eq(userId), any(), any())).thenReturn(List.of());
        when(transactionRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());
        when(pdfGenerator.render(anyString(), any(ReportData.class))).thenReturn(new byte[]{9});

        byte[] result = service.generatePdf(userId, null, null);

        assertThat(result).isEqualTo(new byte[]{9});

        ArgumentCaptor<ReportData> captor = ArgumentCaptor.forClass(ReportData.class);
        verify(pdfGenerator).render(anyString(), captor.capture());
        ReportData data = captor.getValue();

        assertThat(data.startDate()).isEqualTo(LocalDate.now().withDayOfMonth(1));
        assertThat(data.endDate()).isEqualTo(LocalDate.now());
        assertThat(data.totalIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(data.totalExpense()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(data.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(data.categoryBreakdown()).isEmpty();
        assertThat(data.transactions()).isEmpty();
        assertThat(data.goals()).isEmpty();
    }

    @Test
    void generatePdf_moreThan500Transactions_truncatesListAndFlagsTruncation() {
        when(transactionRepository.getSummary(eq(userId), any(), any())).thenReturn(List.of());
        when(transactionRepository.getExpenseByCategory(eq(userId), any(), any())).thenReturn(List.of());

        List<Transaction> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(Transaction.builder()
                    .id(UUID.randomUUID()).description("Tx " + i).amount(BigDecimal.ONE)
                    .type(TransactionType.EXPENSE).category(CategoryType.FOOD)
                    .timestamp(LocalDateTime.of(2026, 8, 1, 0, 0).plusMinutes(i)).build());
        }
        when(transactionRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(many);
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());
        when(pdfGenerator.render(anyString(), any(ReportData.class))).thenReturn(new byte[]{1});

        service.generatePdf(userId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        ArgumentCaptor<ReportData> captor = ArgumentCaptor.forClass(ReportData.class);
        verify(pdfGenerator).render(anyString(), captor.capture());
        ReportData data = captor.getValue();

        assertThat(data.transactionsTruncated()).isTrue();
        assertThat(data.transactions()).hasSize(500);
        assertThat(data.totalTransactionCount()).isEqualTo(501);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.ReportServiceImplTest"`
Expected: FAIL — compile error, `ReportService`/`ReportServiceImpl` do not exist yet.

- [ ] **Step 3: Create the `ReportService` interface**

```java
package com.piggy.piggyfinance.service;

import java.time.LocalDate;
import java.util.UUID;

public interface ReportService {
    byte[] generatePdf(UUID userId, LocalDate startDate, LocalDate endDate);
}
```

- [ ] **Step 4: Create `ReportServiceImpl.java`**

```java
package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.Transaction;
import com.piggy.piggyfinance.model.dto.CategoryBreakdownItem;
import com.piggy.piggyfinance.model.dto.CategoryTotal;
import com.piggy.piggyfinance.model.dto.GoalProgressItem;
import com.piggy.piggyfinance.model.dto.ReportData;
import com.piggy.piggyfinance.model.dto.TransactionLineItem;
import com.piggy.piggyfinance.model.filters.TransactionFilter;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.TransactionRepository;
import com.piggy.piggyfinance.repository.specifications.TransactionSpecification;
import com.piggy.piggyfinance.service.EntitlementService;
import com.piggy.piggyfinance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private static final int MAX_TRANSACTIONS = 500;
    private static final String TEMPLATE_NAME = "reports/financial-report";

    private final TransactionRepository transactionRepository;
    private final GoalRepository goalRepository;
    private final EntitlementService entitlementService;
    private final ReportPdfGenerator pdfGenerator;

    @Override
    public byte[] generatePdf(UUID userId, LocalDate startDate, LocalDate endDate) {
        entitlementService.requireTier(userId, SubscriptionTier.ESSENCIAL);

        LocalDate from = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1);
        LocalDate to = endDate != null ? endDate : LocalDate.now();

        if (from.isAfter(to)) {
            throw new BusinessException("startDate must not be after endDate");
        }

        ReportData reportData = buildReportData(userId, from, to);
        return pdfGenerator.render(TEMPLATE_NAME, reportData);
    }

    private ReportData buildReportData(UUID userId, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX);

        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (var item : transactionRepository.getSummary(userId, start, end)) {
            if (item.type() == TransactionType.INCOME) {
                totalIncome = item.total();
            } else if (item.type() == TransactionType.EXPENSE) {
                totalExpense = item.total();
            }
        }
        BigDecimal balance = totalIncome.subtract(totalExpense);

        List<CategoryTotal> categoryTotals = transactionRepository.getExpenseByCategory(userId, start, end);
        BigDecimal finalTotalExpense = totalExpense;
        List<CategoryBreakdownItem> categoryBreakdown = categoryTotals.stream()
                .map(c -> new CategoryBreakdownItem(c.category(), c.total(), percentageOf(c.total(), finalTotalExpense)))
                .toList();

        TransactionFilter filter = new TransactionFilter();
        filter.setStartDate(from);
        filter.setEndDate(to);
        List<Transaction> allTransactions = transactionRepository.findAll(
                TransactionSpecification.byFilter(filter, userId),
                Sort.by(Sort.Direction.ASC, "timestamp"));

        long totalTransactionCount = allTransactions.size();
        boolean truncated = totalTransactionCount > MAX_TRANSACTIONS;
        List<TransactionLineItem> transactionLines = allTransactions.stream()
                .limit(MAX_TRANSACTIONS)
                .map(t -> new TransactionLineItem(
                        t.getTimestamp().toLocalDate(), t.getDescription(), t.getCategory(), t.getType(), t.getAmount()))
                .toList();

        List<Goal> goals = goalRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<GoalProgressItem> goalItems = goals.stream()
                .map(g -> new GoalProgressItem(
                        g.getName(), g.getCurrentAmount(), g.getTargetAmount(),
                        percentageOf(g.getCurrentAmount(), g.getTargetAmount())))
                .toList();

        return new ReportData(from, to, totalIncome, totalExpense, balance,
                categoryBreakdown, transactionLines, truncated, totalTransactionCount, goalItems);
    }

    private static BigDecimal percentageOf(BigDecimal part, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.piggy.piggyfinance.service.ReportServiceImplTest"`
Expected: PASS (all 5 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/service/ReportService.java \
        src/main/java/com/piggy/piggyfinance/service/impl/ReportServiceImpl.java \
        src/test/java/com/piggy/piggyfinance/service/ReportServiceImplTest.java
git commit -m "feat: add ReportService with tier gate, validation, and aggregation"
```

---

### Task 5: `ReportController`

**Files:**
- Create: `src/main/java/com/piggy/piggyfinance/controller/ReportController.java`
- Test: `src/test/java/com/piggy/piggyfinance/controller/ReportControllerTest.java`

**Interfaces:**
- Consumes: `ReportService.generatePdf(UUID, LocalDate, LocalDate)` from Task 4.
- Produces: `GET /api/v1/reports/pdf?startDate=&endDate=` → `ResponseEntity<byte[]>` with `Content-Type: application/pdf` and a `Content-Disposition: attachment` header.

Note on test style: this codebase currently has **zero** controller tests and no `spring-security-test` dependency (only `spring-boot-starter-webmvc-test`, unused so far). Rather than introduce a full `@WebMvcTest` + security-mocking setup that has no precedent anywhere else in the project, this controller is tested as a plain unit test — the same style used for every other class in this codebase (`@ExtendWith(MockitoExtension.class)`, mocked collaborator, direct method call). This still verifies everything the design spec cares about: response status, `Content-Type`, `Content-Disposition`, and that a locked-tier error propagates correctly (Spring's existing `GlobalExceptionHandler` — already covered by no other test either — is what actually maps that exception to HTTP in production).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/piggy/piggyfinance/controller/ReportControllerTest.java`:

```java
package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    @Mock ReportService reportService;
    @InjectMocks ReportController controller;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @Test
    void generatePdf_returnsOkWithPdfContentTypeAndDisposition() {
        byte[] pdfBytes = {1, 2, 3};
        when(reportService.generatePdf(eq(userId), any(), any())).thenReturn(pdfBytes);

        ResponseEntity<byte[]> response = controller.generatePdf(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(response.getBody()).isEqualTo(pdfBytes);
    }

    @Test
    void generatePdf_serviceThrowsFeatureLocked_propagatesException() {
        when(reportService.generatePdf(eq(userId), any(), any()))
                .thenThrow(new FeatureLockedException("This feature requires the ESSENCIAL plan", SubscriptionTier.ESSENCIAL));

        assertThatThrownBy(() -> controller.generatePdf(null, null, userId))
                .isInstanceOf(FeatureLockedException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.piggy.piggyfinance.controller.ReportControllerTest"`
Expected: FAIL — compile error, `ReportController` does not exist.

- [ ] **Step 3: Create `ReportController.java`**

```java
package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generatePdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal UUID userId) {
        byte[] pdf = reportService.generatePdf(userId, startDate, endDate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"relatorio-piggyfinance.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.piggy.piggyfinance.controller.ReportControllerTest"`
Expected: PASS (both tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piggy/piggyfinance/controller/ReportController.java \
        src/test/java/com/piggy/piggyfinance/controller/ReportControllerTest.java
git commit -m "feat: add GET /api/v1/reports/pdf endpoint"
```

---

### Task 6: Full verification

**Files:** none (verification only)

**Interfaces:** none — this task exercises Tasks 1–5 together.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (existing suite + the 6 new tests added across Tasks 3–5).

- [ ] **Step 2: Run a full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Confirm no `SecurityConfig` gap**

Open `src/main/java/com/piggy/piggyfinance/config/SecurityConfig.java` and confirm `/api/v1/reports/**` is not explicitly listed as `permitAll()` anywhere — it must fall through to `.anyRequest().authenticated()`, same as `/api/v1/transactions` and `/api/v1/goals`. No code change expected; this is a read-only confirmation.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running locally against a real database and an Essencial/Pro test user's JWT:

```bash
curl -sS -D - -o /tmp/report.pdf \
  -H "Authorization: Bearer <jwt>" \
  "http://localhost:8080/api/v1/reports/pdf?startDate=2026-08-01&endDate=2026-08-31"
file /tmp/report.pdf
```

Expected: `HTTP/1.1 200`, `Content-Type: application/pdf`, and `file` reports `PDF document`. Open the file and confirm the four sections render with real data and PT-BR formatting.

No commit for this task (verification only, no code changes expected).
