# Flutter Migration — Task Breakdown Design Spec

**Date:** 2026-05-15
**Status:** Approved

---

## Context

The PiggyFinance frontend is currently a React/TypeScript web app (Vite + Tailwind + shadcn/ui) generated via Lovable, located at `projects/flutter/piggyapp`. The backend is a Spring Boot REST API (Java 21, PostgreSQL) hosted on EasyPanel.

This document defines the granular task breakdown for migrating the frontend to Flutter, targeting **Android + iOS + Web**, organized into 6 sprints with a Jira-compatible CSV export.

---

## Current Frontend — Feature Map

| Screen | Backend support | Notes |
|--------|:--------------:|-------|
| Welcome | — | Static |
| Login | POST /api/auth/login | ✅ |
| Register | POST /api/auth/register | ✅ |
| Dashboard | GET /transactions/summary, /transactions, /users/me | ✅ |
| Transactions | GET /api/v1/transactions (paginated) | ✅ |
| AddTransaction | POST /api/v1/transactions/app | ✅ |
| Charts | GET /transactions/summary (date filters) | ✅ |
| Goals | **None — local React state only** | ❌ needs backend |
| Profile | GET /users/me | WhatsApp linking endpoint exists but no UI ❌ |

---

## Architecture Decisions

- **State management:** Riverpod (AsyncNotifier pattern)
- **Routing:** go_router with redirect guard (checks JWT on every protected route)
- **HTTP:** Dio with Bearer token interceptor
- **JWT storage:** flutter_secure_storage (Android/iOS) + fallback for Web
- **Charts:** fl_chart
- **Theme:** ThemeData with primaryColor `#642a91`, font Gabarito, ThemeMode persisted via SharedPreferences
- **Project structure:** `features/`, `core/` (theme, router, http), `shared/` (widgets, utils)

---

## Sprint Plan

| Sprint | Epics | Goal |
|--------|-------|------|
| **1** | Setup & Infraestrutura, Autenticação | App Flutter rodando com login/registro nas 3 plataformas |
| **2** | Dashboard, Transações | Usuário vê saldo, lança e lista transações |
| **3** | Gráficos e Análises | Tela de Charts com fl_chart |
| **4** | Metas — Backend + Flutter | CRUD de metas persistido no banco |
| **5** | Perfil & WhatsApp Linking | Perfil completo e vínculo WhatsApp funcional |
| **6** | QA & Deploy | APK, IPA e Docker Web em produção |

---

## Epics

1. Setup & Infraestrutura Flutter
2. Autenticação
3. Dashboard
4. Transações
5. Gráficos e Análises
6. Metas — Backend Spring Boot
7. Metas — Flutter
8. Perfil & WhatsApp Linking
9. QA & Deploy

---

## Goals Backend — New Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/v1/goals | Criar meta |
| GET | /api/v1/goals | Listar metas do usuário autenticado |
| PUT | /api/v1/goals/{id} | Editar meta |
| DELETE | /api/v1/goals/{id} | Apagar meta |
| PATCH | /api/v1/goals/{id}/progress | Adicionar valor ao currentAmount |

**DB migration V6:**
```sql
CREATE TABLE goals (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id),
    name           VARCHAR(100) NOT NULL,
    target_amount  NUMERIC(12,2) NOT NULL,
    current_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    icon           VARCHAR(10) NOT NULL DEFAULT '🎯',
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_goals_user_id ON goals(user_id);
```

**Goals list — no pagination.** Goals are returned as a flat `List<GoalResponse>` (no `Page<>`). Rationale: users are not expected to have hundreds of goals; a flat list simplifies the Flutter UI. If scale requires it, pagination can be added in a future milestone.

**Status and deadline — out of scope for this migration.** Goal completion is derived from `currentAmount >= targetAmount` at read time; no explicit `status` column is stored. Deadline is not supported in this milestone — the UI shows a progress bar only. Both can be added post-launch.

---

## CSV Export

The Jira-compatible CSV is located at:
`docs/piggyfinance-flutter-migration-jira.csv`

**Import instructions (Jira Cloud):**
1. Open your Jira project → Backlog view → click **"..."** (more actions) → **"Import issues"** → **"From CSV"**. (Alternative: top nav → **Your Work** → **Import**.)
2. Upload `piggyfinance-flutter-migration-jira.csv` and follow the field-mapping wizard.
3. Map columns: Summary, Issue Type, Priority, Description, Story Points, Sprint, Epic Name, Epic Link.
4. Import **Epics first** in a separate pass (filter rows where Issue Type = Epic), then import Stories/Tasks. This ensures Epic keys exist before Epic Link resolution.
5. Sprint names will be auto-created if they don't exist in the project board.

> **Note:** The path Settings → System → External System Import applies to **Jira Server/Data Center only**, not Jira Cloud.

**Total issues:** 94 (9 Epics + 85 Stories/Tasks)

---

## Out of Scope (this migration)

- Subscription payment logic (plans shown as static UI with "Em breve" badge)
- Categories management screen (stub, no backend)
- Preferences screen (stub)
- Push notifications
- Goal deadlines and explicit status field (completion derived from `currentAmount >= targetAmount` at read time)

---

## Clarifications

**Transaction categories (AddTransaction screen):** Categories are **hardcoded** in the Flutter app — same 9 values as the React app (Alimentação, Transporte, Moradia, Saúde, Educação, Lazer, Assinaturas, Viagens, Outros). No categories endpoint is called. The backend `CategoryType` enum already maps these values.

**iOS build (Sprint 6):** The sprint task covers a local `flutter build ios --release --no-codesign` to verify the build compiles. Distributable `.ipa` production (Ad Hoc / TestFlight / App Store) requires an Apple Developer account and is **not in scope for this sprint** — it should be planned as a separate release task once the account is set up.