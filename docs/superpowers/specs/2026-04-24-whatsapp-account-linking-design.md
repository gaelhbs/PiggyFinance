# WhatsApp Account Linking — Design Spec

**Date:** 2026-04-24
**Status:** Approved

---

## Problem

Transactions arriving via WhatsApp need to be linked to a specific user in the database. The current approach passes `userEmail` in the request body, which requires n8n to know the user's email — information that is not reliably available from a WhatsApp message. The sender's phone number, however, is always present and cannot be spoofed.

---

## Solution Overview

Two-phase design:

1. **Linking (once):** The user generates a short-lived code in the app and sends it via WhatsApp. The backend links their phone number to their account.
2. **Usage (every transaction):** n8n extracts the sender's phone number and sends it with each transaction. The backend resolves the user by phone number.

---

## Data Model

### `users` table — new column

```sql
ALTER TABLE users ADD COLUMN phone_number VARCHAR(20) UNIQUE;
```

- Nullable: existing users without WhatsApp linking are unaffected.
- Unique: one account per phone number.

### New table: `whatsapp_link_codes`

```sql
CREATE TABLE whatsapp_link_codes (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id),
    code       VARCHAR(10) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_whatsapp_link_codes_user_id ON whatsapp_link_codes(user_id);
```

- Code expires after 15 minutes.
- Single-use: `used = true` after confirmation.
- Records are retained for audit purposes, not deleted.

---

## API Changes

### New: `POST /api/v1/users/whatsapp/link/generate`

- **Auth:** JWT (app user)
- **Request body:** none
- **Response:**
  ```json
  {
    "code": "PIGGY-482193",
    "expiresAt": "2026-04-24T15:30:00Z"
  }
  ```
- **Behavior:**
  - If the authenticated user already has a **linked phone number**, return `409` with `errorCode: "ALREADY_LINKED"`.
  - If the user has an **existing unexpired, unused code**, return that code instead of generating a new one.
  - Otherwise, generate a random **6-digit** code in the format `PIGGY-XXXXXX`, store it with the user's ID and a 15-minute expiry.
  - Rate limiting: at most one active code per user at a time (enforced by the "return existing" rule above). Generating after a code expires or is used is always allowed.
- `expiresAt` is always returned in ISO-8601 UTC format (e.g., `2026-04-24T15:30:00Z`).

### New: `POST /api/v1/users/whatsapp/link/confirm`

- **Auth:** API Key (n8n)
- **Request body:**
  ```json
  {
    "phoneNumber": "+5511999999999",
    "code": "PIGGY-482193"
  }
  ```
- **Success response `200 OK`:**
  ```json
  { "message": "Account linked successfully." }
  ```
- **Error cases (all include `errorCode` field in body):**

  | HTTP | `errorCode` | Meaning |
  |------|-------------|---------|
  | `404` | `CODE_NOT_FOUND` | Code does not exist |
  | `410` | `CODE_EXPIRED` | Code exists but is past `expires_at` |
  | `422` | `CODE_ALREADY_USED` | Code was already consumed |
  | `409` | `PHONE_ALREADY_LINKED` | Phone number is linked to a different account |

- **Behavior:** validates in order:
  1. Code exists → else `404 CODE_NOT_FOUND`
  2. Code not expired → else `410 CODE_EXPIRED`
  3. Code not used → else `422 CODE_ALREADY_USED`
  4. Destination phone not linked to another account → else `409 PHONE_ALREADY_LINKED`
  5. Code's owner (`user_id`) does not already have a phone number → else `409 ALREADY_LINKED`
  6. Sets `users.phone_number = phoneNumber`, marks code as `used = true`.

### Modified: `POST /api/v1/transactions/whatsapp`

- **Change:** replaces `userEmail` with `phoneNumber` in `CreateWhatsAppTransactionRequest`.
- **Request body (new):**
  ```json
  {
    "phoneNumber": "+5511999999999",
    "description": "almoço",
    "amount": 35.00,
    "type": "EXPENSE",
    "category": "FOOD"
  }
  ```
- **Behavior:** backend resolves the user by `phoneNumber`. Returns `404` with `errorCode: "PHONE_NOT_LINKED"` if no account is associated, so n8n can forward a helpful message to the user.

---

## Linking Flow

```
[App — Settings screen]
        |
        | POST /api/v1/users/whatsapp/link/generate  (JWT)
        v
[Backend] generates PIGGY-XXXXXX → stores in whatsapp_link_codes
        |
        | returns { code, expiresAt }
        v
[App shows] "Envie este código pelo WhatsApp: PIGGY-482193 (válido por 15 min)"
        |
[User sends "PIGGY-482193" via WhatsApp]
        |
[n8n: message matches /^PIGGY-\d{6}$/]
        |
        | POST /api/v1/users/whatsapp/link/confirm  (API Key)
        | { phoneNumber: senderPhone, code: "PIGGY-482193" }
        v
[Backend] validates → saves phoneNumber to user → marks code used
        |
[n8n sends WhatsApp message] "Conta vinculada com sucesso!"
```

---

## Transaction Flow (after linking)

```
[User sends transaction message via WhatsApp]
        |
[n8n: message does NOT match PIGGY-XXXXXX pattern]
        |
[n8n interprets message as transaction via LLM/parsing]
        |
        | POST /api/v1/transactions/whatsapp  (API Key)
        | { phoneNumber, description, amount, type, category }
        v
[Backend] resolves user by phoneNumber → creates transaction
```

---

## n8n Workflow Change

Add a condition node as the first step after receiving a WhatsApp message:

```
IF message matches /^PIGGY-\d{6}$/
  → call POST /api/v1/users/whatsapp/link/confirm
  → on success: reply "Conta vinculada com sucesso!"
  → on error: reply based on errorCode (see Error Handling table)
ELSE
  → existing transaction interpretation flow (unchanged)
```

---

## Error Handling

| Scenario | `errorCode` | HTTP | n8n reply |
|---|---|---|---|
| Code not found | `CODE_NOT_FOUND` | `404` | "Código inválido." |
| Code expired | `CODE_EXPIRED` | `410` | "Código expirado. Gere um novo no app." |
| Code already used | `CODE_ALREADY_USED` | `422` | "Código já utilizado." |
| Phone already linked elsewhere | `PHONE_ALREADY_LINKED` | `409` | "Número já vinculado a outra conta." |
| User already has phone linked | `ALREADY_LINKED` | `409` | (both generate and confirm endpoints — app/n8n handles this) |
| Transaction with unlinked phone | `PHONE_NOT_LINKED` | `404` | "Conta não vinculada. Use o app para vincular seu WhatsApp." |

---

## Security Notes

- Code format `PIGGY-XXXXXX` (6 digits) = 1,000,000 combinations. Combined with 15-minute expiry and single-use enforcement, brute-force is not practical in the intended use context.
- The confirm endpoint is protected by API Key — only n8n can call it, limiting the attack surface.

---

## Out of Scope

- Frontend (app) screen for the linking flow — this spec covers the backend and n8n contract only.
- Unlinking / changing phone number — future work.
- Multiple phone numbers per account — not needed now.
- Formal rate limiting middleware — acceptable given API Key restriction and single-active-code-per-user rule.
