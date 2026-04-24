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
    expires_at TIMESTAMP NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE
);
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
    "code": "PIGGY-4821",
    "expiresAt": "2026-04-24T15:30:00"
  }
  ```
- **Behavior:** generates a random 4–6 digit code in the format `PIGGY-XXXX`, stores it with the authenticated user's ID and a 15-minute expiry. If the user already has an unexpired, unused code, return that instead of generating a new one.

### New: `POST /api/v1/users/whatsapp/link/confirm`

- **Auth:** API Key (n8n)
- **Request body:**
  ```json
  {
    "phoneNumber": "+5511999999999",
    "code": "PIGGY-4821"
  }
  ```
- **Response:** `200 OK` on success
- **Error cases:**
  - `404` — code not found
  - `410` — code expired
  - `409` — code already used
  - `409` — phone number already linked to another account
- **Behavior:** validates code, sets `users.phone_number = phoneNumber`, marks code as `used = true`.

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
- **Behavior:** backend resolves the user by `phoneNumber`. Returns `404` with a friendly message if the phone number is not linked to any account, so n8n can forward the error to the user in WhatsApp.

---

## Linking Flow

```
[App — Settings screen]
        |
        | POST /api/v1/users/whatsapp/link/generate  (JWT)
        v
[Backend] generates PIGGY-XXXX → stores in whatsapp_link_codes
        |
        | returns { code, expiresAt }
        v
[App shows] "Envie este código pelo WhatsApp: PIGGY-4821 (válido por 15 min)"
        |
[User sends "PIGGY-4821" via WhatsApp]
        |
[n8n: message matches /^PIGGY-\d{4,6}$/]
        |
        | POST /api/v1/users/whatsapp/link/confirm  (API Key)
        | { phoneNumber: senderPhone, code: "PIGGY-4821" }
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
[n8n: message does NOT match PIGGY-XXXX pattern]
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
IF message matches /^PIGGY-\d{4,6}$/
  → call POST /api/v1/users/whatsapp/link/confirm
  → reply to user: "Conta vinculada!" or forward error
ELSE
  → existing transaction interpretation flow (unchanged)
```

---

## Error Handling

| Scenario | Backend response | n8n action |
|---|---|---|
| Code not found | `404` | Reply: "Código inválido." |
| Code expired | `410` | Reply: "Código expirado. Gere um novo no app." |
| Code already used | `409` | Reply: "Código já utilizado." |
| Phone already linked elsewhere | `409` | Reply: "Número já vinculado a outra conta." |
| Transaction with unlinked phone | `404` | Reply: "Conta não vinculada. Use o app para vincular seu WhatsApp." |

---

## Out of Scope

- Frontend (app) screen for the linking flow — this spec covers the backend and n8n contract only.
- Unlinking / changing phone number — future work.
- Multiple phone numbers per account — not needed now.
