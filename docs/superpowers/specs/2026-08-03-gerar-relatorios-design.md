# Gerar Relatórios — Design Spec

**Status:** Aprovado (design) — pronto para virar plano de implementação
**Data:** 2026-08-03
**Escopo deste spec:** Backend-only (Spring Boot). Endpoint de exportação de relatório em PDF.
**Repo:** `/Users/gabrielbraga/Documents/Projects/java/PiggyFinance`

---

## 1. Objetivo

Permitir que usuários dos tiers **Essencial** e **Pro** exportem um relatório financeiro em PDF de um período escolhido, cobrindo resumo financeiro, gastos por categoria, extrato detalhado de transações e progresso de metas. É o 4º e último subsistema do lançamento (ver decomposição em memória do projeto — Checkout & Planos e Fluxo n8n já implementados; Email de confirmação segue pendente, subsistema à parte).

O tier **Free** não tem acesso a relatórios — decisão já tomada no design de Checkout & Planos.

### Não-objetivos (fora deste spec)

- Frontend / tela de geração e download do relatório no app Flutter (spec próprio depois, se necessário).
- Outros formatos de exportação (CSV, XLSX) — apenas PDF neste MVP.
- Persistência/histórico de relatórios gerados — cada geração é sob demanda, sem guardar arquivo ou registro no banco.
- Relatórios agendados/recorrentes (ex.: envio automático mensal por e-mail).
- Gráficos/visualizações dentro do PDF (o relatório usa tabelas e texto, não charts).

---

## 2. Modelo de acesso

Mesmo gate de tier já usado no fluxo n8n (`entitlementService.requireTier`):

```java
entitlementService.requireTier(userId, SubscriptionTier.ESSENCIAL);
```

Usuários Free recebem `FeatureLockedException`, já mapeada pelo `GlobalExceptionHandler` existente para `FeatureLockedResponse` (mesmo contrato de erro usado hoje pelo gate de PRO no WhatsApp) — nenhum handler novo é necessário.

---

## 3. Endpoint

Novo `ReportController`, para não misturar responsabilidade de CRUD de transação com geração de relatório:

```
GET /api/v1/reports/pdf?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
```

- Autenticado via `@AuthenticationPrincipal UUID userId` (mesmo padrão do `TransactionController`).
- `startDate`/`endDate` opcionais: default para o mês corrente (mesmo default de `/transactions/summary`), garantindo consistência entre os dois endpoints.
- Se `startDate > endDate` → `BusinessException` (400), mesmo padrão de validação usado em `TransactionServiceImpl`.
- Resposta: `ResponseEntity<byte[]>` com `Content-Type: application/pdf` e `Content-Disposition: attachment; filename="relatorio-piggyfinance-<start>-<end>.pdf"`.

---

## 4. Geração do PDF

**Abordagem escolhida:** Thymeleaf (template HTML/CSS) + [openhtmltopdf](https://github.com/danfickle/openhtmltopdf) (`openhtmltopdf-pdfbox`) para converter o HTML renderizado em PDF.

Motivo: estilizar seções e tabelas em HTML/CSS é mais rápido e legível de manter do que desenho programático (PDFBox puro), e evita a complexidade de um motor de relatórios completo (JasperReports), que seria overkill para um documento de 4 seções.

Novas dependências no `build.gradle`:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
implementation 'io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10'
```

Fluxo interno do `ReportServiceImpl.generatePdf(userId, startDate, endDate)`:

1. `entitlementService.requireTier(userId, SubscriptionTier.ESSENCIAL)`.
2. Validar `startDate <= endDate`.
3. Montar `ReportData` (DTO não persistido) agregando os 4 blocos de conteúdo (seção 5).
4. Popular o template Thymeleaf (`templates/reports/financial-report.html`) com `ReportData`.
5. Renderizar HTML → string via `TemplateEngine` (modo `HTML`, sem contexto web).
6. Converter HTML → PDF via `PdfRendererBuilder` (openhtmltopdf), retornando `byte[]`.

Conteúdo do PDF em **PT-BR**: valores monetários formatados via `NumberFormat.getCurrencyInstance(new Locale("pt", "BR"))`, datas em `dd/MM/yyyy`.

---

## 5. Conteúdo do relatório

`ReportData` agrega, reaproveitando serviços/repositórios existentes sempre que possível:

1. **Resumo financeiro do período** — reaproveita `TransactionRepository.getSummary()` (já existe, usado por `/transactions/summary`): income, expense, balance.
2. **Gastos por categoria** — nova query agregada em `TransactionRepository`, agrupando por `category` com `type = EXPENSE` no período (mesmo padrão da query de summary existente). O service calcula o percentual de cada categoria sobre o total de despesas.
3. **Lista detalhada de transações** — reaproveita `TransactionSpecification` (já usado em `listTransactions`), buscando todas as transações do período sem paginação, ordenadas por `timestamp`. **Teto de segurança: 500 transações.** Se o período exceder esse limite, a lista é truncada nas primeiras 500 e uma nota é exibida no rodapé da seção ("mostrando as primeiras 500 de N transações — refine o período para ver o extrato completo"). O limite é uma constante ajustável no `ReportServiceImpl`, seguindo o padrão de `FREE_MONTHLY_TRANSACTION_LIMIT` já existente no mesmo pacote.
4. **Progresso de metas** — reaproveita `GoalRepository`, listando todas as metas do usuário (snapshot atual, não filtrado por período — metas não têm data de referência no modelo atual). Exibe `currentAmount`/`targetAmount` e o percentual de progresso.

### Estados vazios

Nenhum dos blocos acima é tratado como erro se vazio:

- Sem transações no período → seção de resumo/categoria/extrato exibe "Nenhuma transação no período".
- Sem metas cadastradas → seção de metas exibe "Nenhuma meta cadastrada".

---

## 6. Tratamento de erros

| Caso | Comportamento |
|---|---|
| Tier Free | `FeatureLockedException` → `FeatureLockedResponse` (já existente) |
| `startDate > endDate` | `BusinessException` → 400 (já existente) |
| Datas omitidas | Default para mês corrente (mesmo comportamento de `/transactions/summary`) |
| Período sem dados | PDF gerado normalmente, com seções em estado vazio |
| Falha de renderização do PDF (bug) | Propaga como 500 genérico via `GlobalExceptionHandler` — não é tratado como caso de negócio |

---

## 7. Testes

Seguindo o padrão TDD já usado no projeto (specs de Checkout & Planos e n8n):

- **`ReportServiceImplTest`** (unitário, repositórios mockados):
  - Tier Free lança `FeatureLockedException`.
  - Agregação correta de resumo, categoria, extrato e metas a partir de dados mockados.
  - Período sem dados gera `ReportData` com seções vazias, sem erro.
  - `startDate > endDate` lança `BusinessException`.
  - Cap de 500 transações aplicado corretamente quando o período excede o limite.
- **`ReportControllerTest`** (slice de teste web, mesmo padrão dos controllers existentes):
  - `200` + `Content-Type: application/pdf` + `Content-Disposition` corretos.
  - Erro mapeado corretamente quando `FeatureLockedException` é lançada.
  - Defaults de data aplicados corretamente quando parâmetros omitidos.
- **Teste de integração leve do PDF gerado:** confirma que os bytes retornados começam com o magic number `%PDF-` e não estão vazios — evita regressão silenciosa em que o template quebra e gera PDF corrompido.
- **Query nova de agregação por categoria:** testada via `@DataJpaTest`, seguindo o padrão dos repositórios existentes (se houver testes de repositório no projeto; caso contrário, cobrir via `ReportServiceImplTest` com banco de teste).

---

## 8. Resumo de decisões

| Decisão | Escolha |
|---|---|
| Escopo | API + exportação de arquivo (não só dados JSON) |
| Conteúdo | Resumo financeiro + gastos por categoria + extrato detalhado + progresso de metas |
| Formato de arquivo | Apenas PDF |
| Gate de tier | Essencial+ (mesmo de Checkout & Planos) |
| Período | Range de datas livre (`startDate`/`endDate`), default mês corrente |
| Persistência | Nenhuma — geração sob demanda |
| Idioma do conteúdo | PT-BR |
| Lib de geração de PDF | Thymeleaf + openhtmltopdf |
