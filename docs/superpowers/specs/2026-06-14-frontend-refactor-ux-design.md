# PiggyFinance — Frontend Refactor & UX Redesign

**Date:** 2026-06-14
**Status:** Approved
**Scope:** React web app (`/Users/gabrielbraga/Documents/Projects/flutter/piggyapp`) + new Goals backend endpoints (Spring Boot)

---

## Context

The current React frontend (Vite + TypeScript + Tailwind + shadcn/ui) was generated via Lovable and has several UX problems:

- All pages locked to `max-w-md mx-auto` — on desktop it renders as a narrow 448px column with wasted space on both sides
- BottomNav uses emoji characters (`🏠`, `🚗`, etc.) in Goals and scattered throughout the app
- Transaction filters hidden behind a toggle icon (poor discoverability)
- Goals screen is entirely local React state with no backend persistence
- No responsive grid adaptation for tablet/desktop
- `AddTransaction` only shows categories for expenses, not income

This refactor keeps the existing visual identity (purple `#642a91`, Gabarito font, dark mode support) while fixing layout, UX patterns, and adding Goals backend integration.

---

## Tech Stack (unchanged)

| Layer | Technology |
|-------|-----------|
| Framework | React 18 + Vite + TypeScript |
| Styling | Tailwind CSS + shadcn/ui |
| Icons | lucide-react (no emojis anywhere) |
| State / data fetching | React Query (already in use) |
| Routing | React Router v6 |
| Backend | Spring Boot (Java 21) + PostgreSQL |

---

## Architecture — Responsive Shell

### `AppLayout` component

New central wrapper for all authenticated pages. Replaces the current per-page `max-w-md mx-auto` pattern.

```
Mobile (< 768px)                   Desktop (≥ 1024px)
┌──────────────────────┐           ┌────────────────────────────────────┐
│  <page content>      │           │  <page content>                    │
│  1-column layout     │           │  max-w-5xl, grid 2–3 cols per page │
│                      │           │                                    │
├──────────────────────┤           ├────────────────────────────────────┤
│  BottomNav pill      │           │  BottomNav pill (max-w-lg centered)│
└──────────────────────┘           └────────────────────────────────────┘
```

- `AppLayout` provides `px-4 md:px-8 pt-6 pb-28` padding and the responsive max-width
- Each page declares its own internal grid via Tailwind responsive classes (`grid-cols-1 md:grid-cols-2`)
- Auth pages (`/welcome`, `/login`, `/register`) keep their own layout and are excluded from `AppLayout`

---

## BottomNav Redesign

**Style:** Pill expanded on active item (option C approved).

**Behavior:**
- Active tab: `bg-primary rounded-full px-4 py-2 flex items-center gap-2` — icon + label
- Inactive tabs: icon only in `text-muted-foreground`, no label
- FAB (`/add`): centered, `bg-primary rounded-full -translate-y-3`, purple glow shadow
- Transition: `transition-all duration-300 ease-out`
- All icons from `lucide-react`: `LayoutDashboard`, `Receipt`, `PlusCircle`, `BarChart3`, `Target`
- Profile accessible via avatar in page headers, not in BottomNav
- On desktop: nav is `max-w-lg mx-auto` — stays compact and centered regardless of screen width

**No emojis anywhere in the application.**

---

## Page Designs

### Dashboard

**Mobile:** Vertical single column — greeting header → balance card → weekly bar chart → recent transactions (3 items) → "ver todas" link.

**Desktop (md:grid-cols-2):**
- Balance card spans full width at top
- Left column: recent transactions list (5 items)
- Right column: weekly bar chart + category pie chart

**Key changes from current:**
- Header avatar links to Profile (already works)
- Bar chart always visible (not buried in a "Charts" preview section)
- "Ver todas" and "Ver todos os gráficos" links removed — content shows inline on desktop, links only on mobile

---

### Transactions

**Mobile + Desktop:**
- Filter chips **always visible** — `Todas | Entradas | Saídas` + category chips scrollable horizontally
- Search bar always visible above chips (not toggleable)
- Date group headers: uppercase, `tracking-wide`, `text-muted-foreground/70`
- Each transaction row: category icon, description, category label, date, amount
- **Swipe-to-delete on mobile:** reveal delete button on left-swipe, confirm via toast with "Desfazer" (undo within 5s)
- **Desktop:** wider layout shows all columns without truncation; hover reveals edit/delete actions on right

---

### AddTransaction

**Mobile + Desktop (centered card, max-w-md):**

1. Toggle Entrada / Saída at top
2. Large amount field — color changes dynamically: `text-income` for income, `text-expense` for expense; label also changes color
3. Description input
4. Category grid (5 columns, lucide icons + label below):
   - **Expenses:** Alimentação (`UtensilsCrossed`), Transporte (`Car`), Saúde (`Heart`), Casa (`Home`), Lazer (`Gamepad2`), Vestuário (`Shirt`), Educação (`BookOpen`), Outros (`MoreHorizontal`)
   - **Income** (new): Salário (`Briefcase`), Freelance (`Laptop`), Investimento (`TrendingUp`), Presente (`Gift`), Outros (`MoreHorizontal`)
5. Date picker
6. Optional note (textarea, collapsed by default, expand on tap)
7. Save button — gradient primary, full width

**After save:** brief success state (check icon + "Registrado"), then form resets. No page navigation.

---

### Goals (full rebuild + backend)

**UI:**

**Header:** Title + "Nova meta" button (pill, top right) — no dashed add button in the list.

**Summary strip (3 metrics in a row):**
- Total investido
- Metas concluídas (N de M)
- % da meta com maior progresso

**Goal card:**
- Icon (Lucide, from predefined set) + name + `currentAmount / targetAmount`
- Progress bar + percentage (right-aligned, bold)
- "Faltam R$ X" below bar
- "Investir nesta meta" button → opens Sheet/Drawer with amount input + confirm
- Completed goals: green progress bar + "Concluída" badge, no invest button
- Three-dot menu (edit / delete) — DropdownMenu

**Desktop:** `grid-cols-1 md:grid-cols-2` for goal cards. Completed goals grouped at the bottom.

**Available goal icons (Lucide, no emojis):**
`Home`, `Car`, `Plane`, `Smartphone`, `GraduationCap`, `PiggyBank`, `Palmtree`, `Target`, `Bike`, `Heart`, `ShoppingBag`, `Laptop`

**Backend — New Spring Boot Endpoints:**

All endpoints are authenticated via JWT (existing `JwtAuthFilter`).

| Method | Path | Request | Response |
|--------|------|---------|---------|
| `POST` | `/api/v1/goals` | `{ name, targetAmount, currentAmount?, iconName }` | `GoalResponse` |
| `GET` | `/api/v1/goals` | — | `List<GoalResponse>` |
| `PUT` | `/api/v1/goals/{id}` | `{ name, targetAmount, iconName }` | `GoalResponse` |
| `DELETE` | `/api/v1/goals/{id}` | — | `204 No Content` |
| `PATCH` | `/api/v1/goals/{id}/progress` | `{ amount }` | `GoalResponse` |

**`Goal` entity fields:** `id` (UUID), `userId` (FK), `name` (String), `targetAmount` (BigDecimal), `currentAmount` (BigDecimal, default 0), `iconName` (String), `createdAt`, `updatedAt`.

**Business rules:**
- `currentAmount` cannot exceed `targetAmount` (clamp at 100%)
- Only the owner can read/edit/delete their own goals (same pattern as transactions)
- `PATCH /progress` adds the given `amount` to `currentAmount` (not a set operation)

**DB migration:** New `V7__goals.sql` — creates `goals` table with indexes on `user_id`.

**Frontend service:** `goalsService.ts` — wraps all 5 endpoints using the existing Axios/fetch client. React Query hooks: `useGoals`, `useCreateGoal`, `useUpdateGoal`, `useDeleteGoal`, `useAddGoalProgress`.

---

### Charts

**No structural redesign** — existing layout is acceptable. Apply:
- Remove `max-w-md` constraint → `max-w-2xl` on desktop
- Period filter chips always visible (same pattern as Transactions)
- Remove any emoji usage if present

---

### Profile

**Layout:**
1. Centered avatar (bordered circle, piggy logo) + name + "Membro desde" + plan badge
2. Upgrade card (purple, compact) — only shown for free users; paid users see "Gerenciar assinatura" instead
3. Plans detail in a Sheet (not inline list) — triggered by the upgrade card
4. Menu list items: WhatsApp, Preferências, Tema toggle (inline switch, not a nav item), Sair

**Theme toggle:** `Switch` component inline in the Preferências row — no separate tap required.

**All menu icons:** Lucide — `MessageCircle` (WhatsApp), `Settings2` (Preferências), `Moon`/`Sun` (Tema), `LogOut` (Sair).

---

### Auth screens (Welcome, Login, Register)

Apply global polish only:
- Remove any emoji characters
- Ensure consistent button sizing and spacing
- No layout restructure needed

---

## Global Rules

| Rule | Detail |
|------|--------|
| No emojis | Replace every emoji in the codebase with a Lucide icon or remove entirely |
| Responsive max-width | Pages use `max-w-5xl` via `AppLayout`; forms use inner `max-w-md` |
| Icons | Only `lucide-react` — no custom SVG inline, no emoji, no `CategoryIcon` custom component |
| Dark mode | All new components must work in both light and dark mode using existing CSS variables |
| No `max-w-md mx-auto` per-page | Removed from all page roots — handled exclusively by `AppLayout` |
| Toast confirmations | Destructive actions (delete transaction, delete goal) use toast with undo, not an AlertDialog |

---

## Implementation Order

1. **Backend — Goals endpoints** (Spring Boot): entity, repository, service, controller, migration V7
2. **Frontend — AppLayout + BottomNav** (shell changes that affect every page)
3. **Goals page** (highest impact, needs backend from step 1)
4. **Transactions page** (filters, swipe-to-delete)
5. **AddTransaction page** (income categories, dynamic color)
6. **Dashboard page** (responsive grid)
7. **Profile page** (avatar layout, toggle, plans sheet)
8. **Charts page** (remove max-w-md, filter chips)
9. **Auth pages** (polish, remove emojis)
10. **Global cleanup** (remove CategoryIcon, remove all emojis, remove per-page max-w-md)

---

## Out of Scope

- Flutter migration (separate project, tracked in `2026-05-15-flutter-migration-task-breakdown-design.md`)
- Push notifications
- Offline mode
- Categories CRUD screen (exists as menu item but not implemented — stays as-is)
- Plan billing/payment flows (upgrade button present but no payment backend)
