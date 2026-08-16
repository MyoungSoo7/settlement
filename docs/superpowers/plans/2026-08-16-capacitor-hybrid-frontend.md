# Capacitor Hybrid Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing Vite React frontend into a browser-compatible Capacitor hybrid app with native-secure authentication storage and tested mobile behavior.

**Architecture:** Keep React and the existing PWA as the source of truth. Put all native behavior behind runtime-safe adapters, and move authentication persistence behind an async storage contract. Add Capacitor platform projects only after the browser code and tests remain green.

**Tech Stack:** React 19, TypeScript 5, Vite 5, Vitest, Playwright, Capacitor 8, official Capacitor App/Keyboard/StatusBar/Push Notifications/Filesystem plugins, Keychain/Keystore secure-storage plugin.

**Spec:** `docs/superpowers/specs/2026-08-16-capacitor-hybrid-frontend-design.md`

## Global Constraints

- Browser/PWA behavior must remain unchanged.
- Access tokens must never use native plaintext preferences; native storage must use iOS Keychain/Android Keystore.
- Do not cache authenticated settlement, payment, or account API responses in the service worker.
- Do not change payment amount, authorization, or settlement semantics.
- Do not claim iOS simulator/device verification on Windows without actual macOS/Xcode/device evidence.
- Do not commit generated secrets, signing certificates, provisioning profiles, or local environment files.

---

### Task 1: Add authentication storage contract and browser adapter

**Files:**
- Create: `frontend/src/lib/authStorage.ts`
- Modify: `frontend/src/api/auth.ts`
- Modify: `frontend/src/api/axios.ts`
- Test: `frontend/src/__tests__/lib/authStorage.test.ts`
- Test: `frontend/src/__tests__/api/auth.test.ts`
- Test: `frontend/src/__tests__/api/axios.test.ts`

**Interfaces:**
- Produces `AuthStorage` with `hydrate()`, `get(key)`, `set(key, value)`, `remove(key)`, and `clear()` returning Promises, plus a synchronous in-memory session cache for existing consumers.
- Produces `getAuthStorage()` returning the runtime-selected adapter.

- [ ] **Step 1: Write failing tests** for browser storage round-trip, logout cleanup, and 401 cleanup through the async adapter.
- [ ] **Step 2: Run focused tests** with `npm run test:run -- src/__tests__/lib/authStorage.test.ts src/__tests__/api/auth.test.ts src/__tests__/api/axios.test.ts`; confirm the new contract is initially missing.
- [ ] **Step 3: Implement the contract** with a browser adapter backed by `window.localStorage`; keep keys typed as the four existing session keys.
- [ ] **Step 4: Change auth and Axios code** so no production auth code directly calls `localStorage`; read the hydrated in-memory cache synchronously and persist writes/cleanup asynchronously.
- [ ] **Step 5: Hydrate before React render** in `main.tsx`; keep the browser path immediate and fail closed if native hydration fails.
- [ ] **Step 6: Run focused tests** and confirm all pass.
- [ ] **Step 7: Commit** with `git add frontend/src frontend/src/__tests__ && git commit -m "refactor: abstract frontend auth storage"`.

### Task 2: Add Capacitor runtime and native project configuration

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/capacitor.config.ts`
- Create: `frontend/src/lib/platform.ts`
- Modify: `frontend/.gitignore` if needed
- Create: `frontend/android/` via `npx cap add android`
- Create: `frontend/ios/` via `npx cap add ios`

**Interfaces:**
- Produces `isNativePlatform()` and `isWebPlatform()` runtime checks without evaluating native modules in jsdom.

- [ ] **Step 1: Install pinned Capacitor 8 packages**: `@capacitor/core@8.5.0`, `@capacitor/cli@8.5.0`, `@capacitor/app@8.1.1`, `@capacitor/keyboard@8.0.5`, `@capacitor/status-bar@8.0.3`, `@capacitor/push-notifications@8.1.2`, and `@capacitor/filesystem@8.1.2` using npm.
- [ ] **Step 2: Implement `capacitor.config.ts`** with `webDir: 'dist'`, stable app id `co.lemuel.app`, and no embedded API secrets.
- [ ] **Step 3: Add Android and iOS platforms** with `npx cap add android` and `npx cap add ios`; inspect generated files for accidental local paths or secrets.
- [ ] **Step 4: Implement runtime detection** using `Capacitor.isNativePlatform()` behind a small module that remains testable in browser/jsdom.
- [ ] **Step 5: Run `npm run typecheck` and `npm run build`** before syncing native projects.
- [ ] **Step 6: Run `npx cap sync`** and verify that generated platform files contain the expected web asset configuration.
- [ ] **Step 7: Commit** with `git add frontend/package.json frontend/package-lock.json frontend/capacitor.config.ts frontend/src/lib/platform.ts frontend/android frontend/ios && git commit -m "feat: add capacitor hybrid app scaffolding"`.

### Task 3: Add native secure storage adapter and native lifecycle bridge

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/src/lib/nativeStorage.ts`
- Create: `frontend/src/lib/nativeLifecycle.ts`
- Modify: `frontend/src/lib/authStorage.ts`
- Modify: `frontend/src/main.tsx`
- Test: `frontend/src/__tests__/lib/nativeStorage.test.ts`
- Test: `frontend/src/__tests__/lib/nativeLifecycle.test.ts`

**Interfaces:**
- `nativeStorage.ts` implements `AuthStorage` using a Keychain/Keystore plugin only on native platforms and delegates to the browser adapter on web.
- `nativeLifecycle.ts` exposes `initializeNativeLifecycle()` and `openExternalUrl(url)`.

- [ ] **Step 1: Select and pin a secure-storage package** compatible with Capacitor 8; verify its published API and native storage guarantees before installation. Do not substitute `@capacitor/preferences` for tokens.
- [ ] **Step 2: Write adapter tests** that mock native plugin calls and prove web fallback is selected outside native runtime.
- [ ] **Step 3: Implement native secure storage** for `access_token`; keep profile metadata in the same adapter initially to avoid split-session races.
- [ ] **Step 4: Implement lifecycle initialization** for status-bar style, keyboard resize behavior, app URL-open events, and external URL handoff.
- [ ] **Step 5: Connect `getAuthStorage()` and `main.tsx`** so initialization occurs before route rendering but does not block browser startup on unavailable native APIs.
- [ ] **Step 6: Run focused tests, `npm run typecheck`, and `npm run build`**.
- [ ] **Step 7: Run `npx cap sync`** and inspect Android/iOS plugin registration.
- [ ] **Step 8: Commit** with `git add frontend/package.json frontend/package-lock.json frontend/src frontend/android frontend/ios && git commit -m "feat: secure native auth storage and lifecycle bridge"`.

### Task 4: Add push registration and explicit file/payment policies

**Files:**
- Create: `frontend/src/lib/pushNotifications.ts`
- Create: `frontend/src/lib/fileTransfer.ts`
- Create: `frontend/src/lib/paymentNavigation.ts`
- Modify: `frontend/src/pages/TossPaymentSuccess.tsx`
- Modify: relevant upload/download pages discovered during implementation
- Test: `frontend/src/__tests__/lib/pushNotifications.test.ts`
- Test: `frontend/src/__tests__/lib/fileTransfer.test.ts`
- Test: `frontend/src/__tests__/lib/paymentNavigation.test.ts`

**Interfaces:**
- `registerForPushNotifications()` requests permission and returns a device token or `null`; it never calls an unverified backend endpoint.
- `downloadFile(blob, filename)` uses browser download on web and the native filesystem/share path only on native.
- `handlePaymentReturn(url)` normalizes web and deep-link success/failure URLs without changing payment values.

- [ ] **Step 1: Inventory existing upload/download/payment call sites** and map each to one adapter; leave screens that already work in WebView untouched.
- [ ] **Step 2: Write tests** for browser download, native plugin mocks, payment success/failure URL parsing, and denied push permission.
- [ ] **Step 3: Implement the adapters** with explicit feature detection and safe failure messages.
- [ ] **Step 4: Add push listeners** without backend registration until an existing compatible endpoint is confirmed.
- [ ] **Step 5: Update Toss return handling** to accept the existing query parameters and Capacitor app URL events; do not auto-open arbitrary URLs.
- [ ] **Step 6: Run focused tests and full `npm run test:run`**.
- [ ] **Step 7: Commit** with `git add frontend/src && git commit -m "feat: add hybrid file payment and notification adapters"`.

### Task 5: Expand responsive and mobile E2E coverage

**Files:**
- Modify: `frontend/playwright.config.ts`
- Modify: `frontend/e2e/mobile-layout.spec.ts`
- Create: `frontend/e2e/hybrid-responsive.spec.ts`

- [ ] **Step 1: Add viewport projects** for 320x800, 375x812, 390x844, and landscape 844x390 using the existing remote base URL.
- [ ] **Step 2: Add route probes** for AI chat, settlement/admin table screens, and admin navigation; skip protected routes only when the existing test fixture cannot authenticate.
- [ ] **Step 3: Assert document width** against viewport width, allowing overflow only inside known scroll containers.
- [ ] **Step 4: Run `npm run e2e:mobile`** and the new targeted project command; record any environment/auth limitations instead of masking failures.
- [ ] **Step 5: Commit** with `git add frontend/playwright.config.ts frontend/e2e && git commit -m "test: cover hybrid mobile responsive layouts"`.

### Task 6: Final verification and handoff documentation

**Files:**
- Modify: `frontend/README.md` or `docs/DEVELOPMENT.md` in the frontend section
- Modify: `docs/superpowers/specs/2026-08-16-capacitor-hybrid-frontend-design.md` if implementation constraints changed

- [ ] **Step 1: Run `npm run typecheck`** and record exit code 0.
- [ ] **Step 2: Run `npm run build`** and record exit code 0.
- [ ] **Step 3: Run `npm run test:run`** and record the complete pass/fail count.
- [ ] **Step 4: Run `npx cap sync`** and record platform sync output.
- [ ] **Step 5: Run mobile E2E** and record actual results, including unavailable iOS/Android tooling.
- [ ] **Step 6: Inspect `git diff --check`, `git status -sb`, and generated-file scope**; ensure no secrets or unrelated changes are included.
- [ ] **Step 7: Commit documentation** with `git add frontend/README.md docs/DEVELOPMENT.md docs/superpowers/specs && git commit -m "docs: document hybrid app verification"` only if documentation changed.
