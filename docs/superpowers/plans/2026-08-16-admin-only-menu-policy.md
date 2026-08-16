# ADMIN-Only Menu Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep system and financially sensitive administrator menus visible and assignable to `ADMIN` only, while preserving existing shared operational menus for `MANAGER`.

**Architecture:** Enforce the policy in the order-service menu domain/application boundary so API callers cannot weaken it. Mirror the same policy in the React menu editor for immediate feedback, and protect the existing role-filtered navigation with regression tests.

**Tech Stack:** Java 25, Spring Boot, JUnit 5, React, TypeScript, Vitest.

**Spec:** Approved chat design: `ADMIN` owns system and sensitive operations; `MANAGER` keeps existing read/operational menus.

## Global Constraints

- Do not change unrelated user modifications.
- Keep menu structure/path changes migration-owned; this change only enforces access policy.
- Backend authorization remains authoritative; hiding or disabling a frontend control is not security.
- Preserve `ADMIN` and `MANAGER` role names and existing menu-route parity.

---

### Task 1: Lock the backend ADMIN-only policy with failing tests

**Files:**
- Modify: `order-service/src/test/java/github/lms/lemuel/menu/domain/MenuTest.java`

- [x] **Step 1: Add a test that rejects a SYSTEM menu without ADMIN**

Add a test creating a `MenuArea.SYSTEM` item with `requiredRole = "MANAGER"`; assert `MenuInvariantViolationException` and an ADMIN-related message.

- [x] **Step 2: Add a test that rejects a sensitive payout menu without ADMIN**

Add a test creating `/admin/payouts` with `requiredRole = "MANAGER"`; assert the same invariant exception.

- [x] **Step 3: Run the focused tests and verify RED**

Run `./gradlew :order-service:test --tests '*MenuTest'`.
Expected: the two new tests fail because the policy is not yet enforced.

---

### Task 2: Enforce ADMIN-only menu policy in the menu domain

**Files:**
- Modify: `order-service/src/main/java/github/lms/lemuel/menu/domain/Menu.java`
- Test: `order-service/src/test/java/github/lms/lemuel/menu/domain/MenuTest.java`

- [x] **Step 1: Add the minimal policy validation**

In `Menu.validate`, require `ADMIN` in the allowed role set for `MenuArea.SYSTEM` and for the existing sensitive paths `/admin/payouts`, `/admin/settlement/chargebacks`, `/admin/settlement/monthly-closing`, `/admin/settlement/commission-rates`, and `/admin/settlement/dlq`.

- [x] **Step 2: Preserve ADMIN-only when another role is also supplied**

Allow `ADMIN,MANAGER` only for non-sensitive areas. For system/sensitive menus, reject any role list that does not resolve to exactly the ADMIN-only policy; do not silently rewrite caller input.

- [x] **Step 3: Run the focused tests and verify GREEN**

Run `./gradlew :order-service:test --tests '*MenuTest'`.
Expected: all MenuTest tests pass, including the new policy tests.

---

### Task 3: Mirror the policy in the ADMIN menu editor

**Files:**
- Modify: `frontend/src/pages/system/MenuManagementPage.tsx`
- Test: `frontend/src/__tests__/pages/MenuManagementPage.test.tsx`

- [x] **Step 1: Add a failing UI test**

When editing a SYSTEM or sensitive menu, assert that only the `ADMIN` role control is available/selected and that the form submits `requiredRole: 'ADMIN'`.

- [x] **Step 2: Implement the editor policy**

Detect ADMIN-only menus from `area === 'SYSTEM'` or the sensitive path set. Force the form role to `ADMIN`, disable non-ADMIN role checkboxes, and show a short explanation. Keep normal menus fully editable.

- [x] **Step 3: Run the focused frontend test**

Run `npm test -- --run src/__tests__/pages/MenuManagementPage.test.tsx` from `frontend`.
Expected: the new test and existing menu editor tests pass.

---

### Task 4: Verify menu parity and repository guards

**Files:**
- No production files unless verification exposes a real mismatch.

- [x] **Step 1: Run order-service tests**

Run `./gradlew :order-service:test`.

- [x] **Step 2: Run frontend tests and production build**

Run `npm test -- --run` and `npm run build` from `frontend`.

- [x] **Step 3: Run menu-route and harness checks**

Run `node --test scripts/harness/test/menu-route-gate.test.mjs` and `node scripts/harness/harness-audit.mjs`.

- [x] **Step 4: Review the final diff**

Run `git status -sb` and `git diff --check`; confirm only the policy implementation, tests, and plan are changed.
