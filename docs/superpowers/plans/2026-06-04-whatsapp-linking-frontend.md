# WhatsApp Linking Frontend UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Vincular WhatsApp" menu item to the Profile screen that lets the user generate a linking code, shows it in a bottom sheet with a tutorial and a direct "Abrir WhatsApp" deep link, and displays a persistent "linked" state once the account is connected.

**Architecture:** The backend `UserResponse` gains a `whatsappLinked: boolean` field derived from `phoneNumber != null`. The frontend fetches this on Profile load via the existing `/users/me` endpoint and uses it to drive two new pieces: a menu item (green ✓ when linked, normal when not) and a `WhatsAppLinkSheet` Drawer component with four internal states — idle, loading, code_shown, already_linked.

**Tech Stack:** Java 21 + Spring Boot 4 (backend), React 18 + TypeScript + Vite + Tailwind CSS + shadcn/ui `Drawer` (vaul) + Lucide icons (frontend). Tests: JUnit 5 + Mockito (backend), Vitest + Testing Library (frontend).

---

## File Map

| Action | Path |
|--------|------|
| Modify | `PiggyFinance/src/main/java/com/piggy/piggyfinance/model/responses/UserResponse.java` |
| Modify | `PiggyFinance/src/main/java/com/piggy/piggyfinance/service/impl/UserServiceImpl.java` |
| Create | `PiggyFinance/src/test/java/com/piggy/piggyfinance/service/UserServiceImplTest.java` |
| Modify | `piggyapp/src/services/api.ts` |
| Create | `piggyapp/src/components/WhatsAppLinkSheet.tsx` |
| Modify | `piggyapp/src/pages/Profile.tsx` |

---

## Task 1: Backend — Add `whatsappLinked` to `UserResponse`

**Files:**
- Modify: `PiggyFinance/src/main/java/com/piggy/piggyfinance/model/responses/UserResponse.java`
- Modify: `PiggyFinance/src/main/java/com/piggy/piggyfinance/service/impl/UserServiceImpl.java`
- Create: `PiggyFinance/src/test/java/com/piggy/piggyfinance/service/UserServiceImplTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/piggy/piggyfinance/service/UserServiceImplTest.java`:

```java
package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.responses.UserResponse;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void getCurrentUser_withNoPhoneNumber_returnsWhatsAppLinkedFalse() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId).name("Gabriel").email("g@test.com")
                .password("hash").createdAt(LocalDateTime.now())
                .phoneNumber(null).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserResponse response = userService.getCurrentUser(userId);

        assertThat(response.whatsappLinked()).isFalse();
    }

    @Test
    void getCurrentUser_withPhoneNumber_returnsWhatsAppLinkedTrue() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId).name("Gabriel").email("g@test.com")
                .password("hash").createdAt(LocalDateTime.now())
                .phoneNumber("+5575981231503").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserResponse response = userService.getCurrentUser(userId);

        assertThat(response.whatsappLinked()).isTrue();
    }

    @Test
    void getCurrentUser_withUnknownId_throwsUserNotFoundException() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser(userId))
                .isInstanceOf(UserNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /Users/gabrielbraga/Documents/Projects/java/PiggyFinance
./gradlew test --tests "com.piggy.piggyfinance.service.UserServiceImplTest"
```

Expected: compilation error — `response.whatsappLinked()` does not exist yet.

- [ ] **Step 3: Add `whatsappLinked` to `UserResponse`**

Replace the entire file `src/main/java/com/piggy/piggyfinance/model/responses/UserResponse.java`:

```java
package com.piggy.piggyfinance.model.responses;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        boolean whatsappLinked
) {}
```

- [ ] **Step 4: Update `UserServiceImpl` to populate the new field**

Replace the `return` statement in `getCurrentUser` in `UserServiceImpl.java`:

```java
return new UserResponse(user.getId(), user.getName(), user.getEmail(),
        user.getPhoneNumber() != null);
```

The full method after the change:

```java
@Override
public UserResponse getCurrentUser(UUID userId) {
    log.debug("Fetching user: {}", userId);

    var user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

    return new UserResponse(user.getId(), user.getName(), user.getEmail(),
            user.getPhoneNumber() != null);
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
./gradlew test --tests "com.piggy.piggyfinance.service.UserServiceImplTest"
```

Expected output:
```
UserServiceImplTest > getCurrentUser_withNoPhoneNumber_returnsWhatsAppLinkedFalse() PASSED
UserServiceImplTest > getCurrentUser_withPhoneNumber_returnsWhatsAppLinkedTrue() PASSED
UserServiceImplTest > getCurrentUser_withUnknownId_throwsUserNotFoundException() PASSED

BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit**

```bash
git -C /Users/gabrielbraga/Documents/Projects/java/PiggyFinance add \
  src/main/java/com/piggy/piggyfinance/model/responses/UserResponse.java \
  src/main/java/com/piggy/piggyfinance/service/impl/UserServiceImpl.java \
  src/test/java/com/piggy/piggyfinance/service/UserServiceImplTest.java
git -C /Users/gabrielbraga/Documents/Projects/java/PiggyFinance commit -m "feat: add whatsappLinked field to UserResponse"
```

---

## Task 2: Frontend — Update `api.ts`

**Files:**
- Modify: `piggyapp/src/services/api.ts`

- [ ] **Step 1: Add `whatsappLinked` to `UserMeResponse`**

In `src/services/api.ts`, find the `UserMeResponse` interface and replace it:

```typescript
export interface UserMeResponse {
  id: string;
  name: string;
  email: string;
  whatsappLinked: boolean;
}
```

- [ ] **Step 2: Add `WhatsAppLinkCodeResponse` type and `generateWhatsAppLinkCode` function**

Append the following at the end of `src/services/api.ts`:

```typescript
// === WhatsApp Linking ===

export interface WhatsAppLinkCodeResponse {
  code: string;
  expiresAt: string; // ISO-8601 UTC, e.g. "2026-06-04T15:30:00Z"
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

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
git add src/services/api.ts
git commit -m "feat: add WhatsApp link code API types and function"
```

---

## Task 3: Frontend — Create `WhatsAppLinkSheet` component

**Files:**
- Create: `piggyapp/src/components/WhatsAppLinkSheet.tsx`

- [ ] **Step 1: Create the component file**

Create `src/components/WhatsAppLinkSheet.tsx` with the following content:

```tsx
import { useEffect, useRef, useState } from 'react';
import { Check, Copy, MessageCircle } from 'lucide-react';
import {
  Drawer,
  DrawerContent,
  DrawerHeader,
  DrawerTitle,
} from '@/components/ui/drawer';
import { generateWhatsAppLinkCode, WhatsAppLinkCodeResponse } from '@/services/api';

const WPP_NUMBER = '5575981231503';
const WPP_DISPLAY = '+55 75 98123-1503';

type SheetState = 'idle' | 'loading' | 'code_shown' | 'already_linked';

interface WhatsAppLinkSheetProps {
  open: boolean;
  onClose: () => void;
  alreadyLinked: boolean;
}

const getSecondsLeft = (expiresAt: string) =>
  Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));

const formatTimer = (seconds: number) => {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${s.toString().padStart(2, '0')} min`;
};

const WhatsAppLinkSheet = ({ open, onClose, alreadyLinked }: WhatsAppLinkSheetProps) => {
  const [state, setState] = useState<SheetState>('idle');
  const [linkData, setLinkData] = useState<WhatsAppLinkCodeResponse | null>(null);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (open) {
      setState(alreadyLinked ? 'already_linked' : 'idle');
      setLinkData(null);
      setError(null);
      setCopied(false);
    }
  }, [open, alreadyLinked]);

  useEffect(() => {
    if (state === 'code_shown' && linkData) {
      setSecondsLeft(getSecondsLeft(linkData.expiresAt));
      timerRef.current = setInterval(() => {
        setSecondsLeft(prev => {
          if (prev <= 1) {
            clearInterval(timerRef.current!);
            return 0;
          }
          return prev - 1;
        });
      }, 1000);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [state, linkData]);

  const handleGenerate = async () => {
    setState('loading');
    setError(null);
    try {
      const data = await generateWhatsAppLinkCode();
      setLinkData(data);
      setState('code_shown');
    } catch (err: unknown) {
      const status = err instanceof Error ? err.message : '';
      if (status === '409') {
        setState('already_linked');
      } else {
        setError('Erro ao gerar código. Tente novamente.');
        setState('idle');
      }
    }
  };

  const handleOpenWhatsApp = () => {
    if (!linkData) return;
    window.open(`https://wa.me/${WPP_NUMBER}?text=${linkData.code}`, '_blank');
  };

  const handleCopy = async () => {
    if (!linkData) return;
    await navigator.clipboard.writeText(linkData.code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <Drawer open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose(); }}>
      <DrawerContent className="max-w-md mx-auto px-4 pb-8">
        <DrawerHeader>
          <DrawerTitle className="flex items-center gap-2">
            <MessageCircle size={20} className="text-primary" />
            Vincular WhatsApp
          </DrawerTitle>
        </DrawerHeader>

        {state === 'idle' && (
          <div className="px-4">
            {error && (
              <p className="text-sm text-destructive mb-4 text-center">{error}</p>
            )}
            <div className="space-y-3 mb-6">
              {[
                'Toque em Gerar código abaixo',
                `Envie o código para ${WPP_DISPLAY} no WhatsApp`,
                'Aguarde a confirmação automática',
              ].map((text, i) => (
                <div key={i} className="flex items-start gap-3">
                  <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center text-primary-foreground text-xs font-bold shrink-0 mt-0.5">
                    {i + 1}
                  </div>
                  <p className="text-sm text-muted-foreground">{text}</p>
                </div>
              ))}
            </div>
            <button
              onClick={handleGenerate}
              className="w-full gradient-primary text-primary-foreground rounded-xl py-3 text-sm font-semibold hover:opacity-95 transition-opacity"
            >
              Gerar código
            </button>
          </div>
        )}

        {state === 'loading' && (
          <div className="flex flex-col items-center justify-center py-12 gap-3">
            <div className="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin" />
            <p className="text-sm text-muted-foreground">Gerando código...</p>
          </div>
        )}

        {state === 'code_shown' && linkData && (
          <div className="px-4">
            <div className="space-y-3 mb-5">
              <div className="flex items-start gap-3">
                <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center text-primary-foreground text-xs font-bold shrink-0 mt-0.5">1</div>
                <p className="text-sm text-muted-foreground">
                  Envie o código abaixo para <strong className="text-foreground">{WPP_DISPLAY}</strong> no WhatsApp
                </p>
              </div>
              <div className="flex items-start gap-3">
                <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center text-primary-foreground text-xs font-bold shrink-0 mt-0.5">2</div>
                <p className="text-sm text-muted-foreground">Aguarde a confirmação automática</p>
              </div>
            </div>
            <div className="bg-muted rounded-xl p-4 text-center mb-1">
              <p className="font-mono text-2xl font-bold tracking-widest text-[hsl(var(--income))]">
                {linkData.code}
              </p>
            </div>
            <p className="text-xs text-muted-foreground text-center mb-5">
              {secondsLeft > 0
                ? `⏱ Expira em ${formatTimer(secondsLeft)}`
                : '⚠️ Código expirado — gere um novo'}
            </p>
            <button
              onClick={handleOpenWhatsApp}
              className="w-full bg-[hsl(var(--income))] text-[hsl(var(--income-foreground))] rounded-xl py-3 text-sm font-semibold flex items-center justify-center gap-2 mb-2 hover:opacity-90 transition-opacity"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347m-5.421 7.403h-.004a9.87 9.87 0 01-5.031-1.378l-.361-.214-3.741.982.998-3.648-.235-.374a9.86 9.86 0 01-1.51-5.26c.001-5.45 4.436-9.884 9.888-9.884 2.64 0 5.122 1.03 6.988 2.898a9.825 9.825 0 012.893 6.994c-.003 5.45-4.437 9.884-9.885 9.884m8.413-18.297A11.815 11.815 0 0012.05 0C5.495 0 .16 5.335.157 11.892c0 2.096.547 4.142 1.588 5.945L.057 24l6.305-1.654a11.882 11.882 0 005.683 1.448h.005c6.554 0 11.89-5.335 11.893-11.893a11.821 11.821 0 00-3.48-8.413z"/>
              </svg>
              Abrir WhatsApp
            </button>
            <button
              onClick={handleCopy}
              className="w-full bg-card text-foreground border border-border rounded-xl py-3 text-sm font-medium flex items-center justify-center gap-2 hover:bg-muted transition-colors"
            >
              {copied
                ? <Check size={15} className="text-[hsl(var(--income))]" />
                : <Copy size={15} />}
              {copied ? 'Copiado!' : 'Copiar código'}
            </button>
          </div>
        )}

        {state === 'already_linked' && (
          <div className="px-4 flex flex-col items-center py-4">
            <div className="w-16 h-16 rounded-full bg-[hsl(var(--income-soft))] flex items-center justify-center mb-4">
              <span className="text-3xl">✅</span>
            </div>
            <p className="text-base font-bold mb-2">WhatsApp já vinculado!</p>
            <p className="text-sm text-muted-foreground text-center mb-6">
              Sua conta está conectada.<br />
              Registre gastos enviando mensagem para{' '}
              <strong className="text-foreground">{WPP_DISPLAY}</strong>
            </p>
            <button
              onClick={onClose}
              className="w-full bg-card text-foreground border border-border rounded-xl py-3 text-sm font-medium hover:bg-muted transition-colors"
            >
              Fechar
            </button>
          </div>
        )}
      </DrawerContent>
    </Drawer>
  );
};

export default WhatsAppLinkSheet;
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
git add src/components/WhatsAppLinkSheet.tsx
git commit -m "feat: add WhatsAppLinkSheet component with 4 states"
```

---

## Task 4: Frontend — Update `Profile.tsx`

**Files:**
- Modify: `piggyapp/src/pages/Profile.tsx`

- [ ] **Step 1: Add imports**

At the top of `src/pages/Profile.tsx`, the existing import from `lucide-react` is:

```tsx
import { Tag, Settings, LogOut, ChevronRight, Moon, Sun, Crown, Zap, Shield, Check, ExternalLink } from 'lucide-react';
```

Replace it with (adds `MessageCircle`):

```tsx
import { Tag, Settings, LogOut, ChevronRight, Moon, Sun, Crown, Zap, Shield, Check, ExternalLink, MessageCircle } from 'lucide-react';
```

Add two new imports after the existing import lines:

```tsx
import { getCurrentUser } from '@/services/api';
import WhatsAppLinkSheet from '@/components/WhatsAppLinkSheet';
```

- [ ] **Step 2: Add `whatsappLinked` and `sheetOpen` state**

In the `Profile` component, after the existing `const [currentPlan] = useState<PlanId>('free');` line, add:

```tsx
const [whatsappLinked, setWhatsappLinked] = useState(false);
const [sheetOpen, setSheetOpen] = useState(false);

useEffect(() => {
  getCurrentUser()
    .then(user => setWhatsappLinked(user.whatsappLinked))
    .catch(() => {});
}, []);
```

- [ ] **Step 3: Add the WhatsApp menu item and sheet**

In the JSX, find the `{/* Menu */}` section. It currently starts with:

```tsx
<div className="space-y-2">
  {menuItems.map(({ icon: Icon, label, sub }) => (
```

Add the WhatsApp item and the `<WhatsAppLinkSheet />` **before** the `{menuItems.map(...)}` block:

```tsx
<div className="space-y-2">
  {/* WhatsApp linking */}
  <button
    onClick={() => setSheetOpen(true)}
    className={`w-full bg-card rounded-xl p-4 shadow-card flex items-center gap-3 text-left ${
      whatsappLinked ? 'border border-[hsl(var(--income)/0.4)]' : ''
    }`}
  >
    <div className={`w-9 h-9 rounded-lg flex items-center justify-center ${
      whatsappLinked
        ? 'bg-[hsl(var(--income-soft))] text-[hsl(var(--income))]'
        : 'bg-secondary text-primary'
    }`}>
      {whatsappLinked ? <Check size={18} /> : <MessageCircle size={18} />}
    </div>
    <div className="flex-1">
      <p className="text-sm font-medium">
        {whatsappLinked ? 'WhatsApp vinculado' : 'Vincular WhatsApp'}
      </p>
      <p className="text-xs text-muted-foreground">
        {whatsappLinked ? 'Conta conectada ao WhatsApp' : 'Registre gastos pelo WhatsApp'}
      </p>
    </div>
    <ChevronRight size={16} className="text-muted-foreground" />
  </button>

  <WhatsAppLinkSheet
    open={sheetOpen}
    onClose={() => setSheetOpen(false)}
    alreadyLinked={whatsappLinked}
  />

  {menuItems.map(({ icon: Icon, label, sub }) => (
```

- [ ] **Step 4: Verify TypeScript compiles**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 5: Start dev server and test manually**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
npm run dev
```

Open `http://localhost:8080` (or the port shown). Navigate to **Perfil**.

**Test checklist — not linked:**
- [ ] Menu shows "Vincular WhatsApp" with MessageCircle icon (purple/secondary)
- [ ] Tap opens bottom sheet in `idle` state with 3-step tutorial
- [ ] "Gerar código" calls `POST /api/v1/users/whatsapp/link/generate` (check Network tab)
- [ ] Sheet transitions to `loading` (spinner) then `code_shown` (code + timer + two buttons)
- [ ] "Abrir WhatsApp" opens `https://wa.me/5575981231503?text=PIGGY-XXXXXX` in new tab
- [ ] "Copiar código" copies code to clipboard; button text changes to "Copiado!" for 2 seconds
- [ ] Timer counts down every second
- [ ] Closing and reopening the sheet resets to `idle`

**Test checklist — already linked (simulate):**
- To test without a real linked account: temporarily hardcode `setWhatsappLinked(true)` in the `useEffect`
- [ ] Menu item shows "WhatsApp vinculado" with green Check icon and green border
- [ ] Tapping opens sheet in `already_linked` state with ✅ icon and correct message
- [ ] "Fechar" closes the sheet
- Revert the hardcode after verifying

- [ ] **Step 6: Commit**

```bash
cd /Users/gabrielbraga/Documents/Projects/flutter/piggyapp
git add src/pages/Profile.tsx
git commit -m "feat: add WhatsApp linking menu item and sheet to Profile"
```
