# WhatsApp Linking — Frontend UI Design Spec

**Date:** 2026-06-04
**Status:** Approved
**Related spec:** `2026-04-24-whatsapp-account-linking-design.md` (backend + n8n contract)

---

## Context

The backend WhatsApp linking flow is fully implemented (see related spec). This spec covers the frontend UI that exposes the linking flow to the user. The current `Profile.tsx` has no WhatsApp linking element. The current `/users/me` response does not expose linking status.

The frontend lives at `projects/flutter/piggyapp` (React + TypeScript + Vite + Tailwind + shadcn/ui).

---

## Goal

Add a "Vincular WhatsApp" entry point on the Profile screen that:
1. Shows the current linking status (linked / not linked) persistently on the page.
2. When not linked: opens a bottom sheet with a 3-step tutorial and a "Gerar código" action.
3. After generating a code: shows the `PIGGY-XXXXXX` code, a countdown timer, an "Abrir WhatsApp" deep-link button, and a "Copiar código" button.
4. When already linked: the Profile menu item turns green with a ✓ — the sheet is still openable but shows a "já vinculado" state instead of the linking flow.

---

## Backend Change Required

### `UserResponse.java`

Add a derived boolean field:

```java
public record UserResponse(
    UUID id,
    String name,
    String email,
    boolean whatsappLinked   // true when phoneNumber != null
) {}
```

### `UserService` / `UserServiceImpl`

Populate `whatsappLinked` from `user.getPhoneNumber() != null` when building the response.

### `UserMeResponse` (frontend type in `api.ts`)

```typescript
export interface UserMeResponse {
  id: string;
  name: string;
  email: string;
  whatsappLinked: boolean;
}
```

No new endpoint needed. The existing `GET /api/v1/users/me` carries the field.

---

## Frontend Changes

### 1. `src/services/api.ts` — new types and function

```typescript
export interface WhatsAppLinkCodeResponse {
  code: string;
  expiresAt: string; // ISO-8601 UTC
}

export async function generateWhatsAppLinkCode(): Promise<WhatsAppLinkCodeResponse> {
  const res = await fetch(`${BASE_URL}/users/whatsapp/link/generate`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`${res.status}`);
  return res.json();
}
```

### 2. `src/components/WhatsAppLinkSheet.tsx` — new component

A bottom sheet (modal overlay) with four internal states:

| State | Trigger | Content |
|---|---|---|
| `idle` | Sheet opens, user not linked | Tutorial (3 steps) + "Gerar código" button |
| `loading` | "Gerar código" tapped | Spinner + "Gerando código..." |
| `code_shown` | API returns 200 | Code display + countdown timer + "Abrir WhatsApp" + "Copiar código" |
| `already_linked` | API returns 409 or `whatsappLinked: true` on open | Success icon + "Já vinculado" message + "Fechar" |

**Tutorial copy (idle state):**
1. Toque em **Gerar código** abaixo
2. Envie o código para **+55 75 98123-1503** no WhatsApp
3. Aguarde a confirmação automática

**After code generation (`code_shown`):**
- Code displayed in monospace, prominent green: `PIGGY-XXXXXX`
- Countdown timer: derived from `expiresAt`, updated every second, format `MM:SS min`
- "Abrir WhatsApp" button: deep link `https://wa.me/5575981231503?text=PIGGY-XXXXXX` (opens WhatsApp app with number and message pre-filled)
- "Copiar código" button: copies code to clipboard

**Already-linked state:**
- Shows ✅ icon + "WhatsApp já vinculado!"
- Sub-text: "Sua conta está conectada. Registre gastos enviando mensagem para +55 75 98123-1503"
- "Fechar" button

**Props:**
```typescript
interface WhatsAppLinkSheetProps {
  open: boolean;
  onClose: () => void;
  alreadyLinked: boolean; // passed from Profile based on /users/me
}
```

When `alreadyLinked: true`, the sheet opens directly in `already_linked` state, skipping `idle`.

### 3. `src/pages/Profile.tsx` — menu item update

Add a "Vincular WhatsApp" / "WhatsApp vinculado" menu item driven by the `whatsappLinked` field from `/users/me`.

**Not linked (`whatsappLinked: false`):**
- Icon: green background + 💬
- Title: "Vincular WhatsApp"
- Sub: "Registre gastos pelo WhatsApp"
- Chevron: visible, item is tappable → opens `WhatsAppLinkSheet`

**Linked (`whatsappLinked: true`):**
- Icon: green background + ✅
- Title: "WhatsApp vinculado"
- Sub: "Conta conectada ao WhatsApp"
- Chevron: still visible, item is tappable → opens `WhatsAppLinkSheet` in `already_linked` state
- Border: subtle green border on the card to draw attention to the linked state

`whatsappLinked` is read from the `FinanceContext` (or a local `useState` populated from `getCurrentUser()` on mount). No polling needed — the value is fetched once on Profile load and is stable until the user navigates away.

---

## WhatsApp Deep Link

Format: `https://wa.me/<number>?text=<code>`

- Number (no spaces, no dashes, with country code): `5575981231503`
- Text: the code string, e.g. `PIGGY-482193`
- Full example: `https://wa.me/5575981231503?text=PIGGY-482193`

On mobile this opens the WhatsApp app with the chat to `+55 75 98123-1503` pre-filled. On web (desktop) it opens `web.whatsapp.com`.

---

## Error Handling

| Scenario | UI response |
|---|---|
| Network error / 5xx | Toast or inline message: "Erro ao gerar código. Tente novamente." Sheet stays open in `idle`. |
| 409 ALREADY_LINKED | Sheet transitions to `already_linked` state |
| 401 Unauthorized | Redirect to `/welcome` (same pattern as existing app) |

---

## State Persistence

- `whatsappLinked` comes from the server (`/users/me`) on every Profile load — no local storage involved.
- After the user sends the code and the bot confirms via n8n, the next time the user opens the Profile the server returns `whatsappLinked: true` and the menu item updates automatically.
- The frontend does **not** poll for confirmation — the update is visible on the next Profile visit.

---

## Out of Scope

- Unlinking / changing phone number — future work.
- In-app confirmation feedback (the bot sends a WhatsApp message confirming the link — no push notification to the app needed now).
- Real-time countdown sync with server — the timer is local, derived from `expiresAt` at generation time.

---

## File Checklist

| File | Change type |
|---|---|
| `PiggyFinance/.../UserResponse.java` | Add `whatsappLinked` field |
| `PiggyFinance/.../UserServiceImpl.java` | Populate `whatsappLinked` |
| `piggyapp/src/services/api.ts` | Add `WhatsAppLinkCodeResponse` type + `generateWhatsAppLinkCode()` + `whatsappLinked` to `UserMeResponse` |
| `piggyapp/src/components/WhatsAppLinkSheet.tsx` | New component |
| `piggyapp/src/pages/Profile.tsx` | Add menu item + sheet integration |
