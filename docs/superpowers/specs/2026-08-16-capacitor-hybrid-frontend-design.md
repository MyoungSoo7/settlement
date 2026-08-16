# Capacitor Hybrid Frontend Design

## Goal

Extend the existing Vite React/TypeScript frontend into a web-first Capacitor application that preserves browser/PWA behavior while providing a safe foundation for Android and iOS hybrid distribution.

## Scope

- Add Capacitor configuration and Android/iOS project scaffolding.
- Replace direct authentication `localStorage` access with an asynchronous storage abstraction: browser-compatible storage on the web and native secure storage in Capacitor.
- Add a platform adapter for keyboard, status bar, deep links, and push-notification registration without importing native-only APIs during browser builds.
- Make file upload/download behavior explicit for browser and native runtimes.
- Audit the Toss Payments WebView flow and document/guard unsupported native payment paths rather than silently changing payment behavior.
- Expand automated responsive checks for 320px, 375px, 390px, and landscape layouts covering AI chat, wide tables, and admin routes.

## Non-goals

- Shipping signed APK/IPA files.
- Running iOS Simulator or Xcode builds on this Windows workspace.
- Implementing a backend push-token endpoint unless an existing endpoint is found and compatible.
- Caching authenticated settlement/payment API responses offline.
- Replacing the existing PWA service worker.

## Architecture

The frontend remains a single web-first React application. Native capabilities are accessed through small adapters that feature-detect Capacitor at runtime; browser execution remains the default path. Authentication storage is centralized behind an async interface so Axios and auth flows do not know whether the token is stored in browser storage or native secure storage.

The initial native implementation uses official Capacitor platform/core plugins where available. Secure token storage must use a maintained native keychain/keystore plugin; `@capacitor/preferences` is not acceptable for the access token because it is a preferences store rather than a security boundary. If no package can support the current Capacitor version cleanly, native secure storage remains an explicit build-time blocker instead of falling back to plaintext storage.

## Authentication

- Define an async `AuthStorage` contract for `access_token`, `user_email`, `user_role`, and `login_timestamp`.
- Browser adapter uses `localStorage` behind the contract to preserve current behavior.
- Capacitor adapter uses native secure storage for the token and may use ordinary preferences for non-sensitive profile metadata.
- Axios request/response interceptors await the storage adapter and clear all session keys on 401.
- Logout and login replacement use the same adapter, avoiding mixed browser/native sessions.
- Existing tests must cover browser behavior and the 401 cleanup path.

## Native capabilities

- Keyboard: use the Capacitor keyboard plugin only when running natively; keep browser CSS safe-area and viewport behavior unchanged.
- Status bar: set a platform-appropriate style/color at startup; do not hard-code a value that harms light/dark accessibility.
- Deep links: normalize incoming URLs and route payment success/failure and password-reset links through React Router. The web URL flow remains unchanged.
- Push: request permission only from an explicit user action or an existing notification preference flow. Register listeners and expose the native token through an adapter; do not invent a backend registration call.

## Files and payments

- Browser file downloads retain normal anchor/blob behavior.
- Native downloads must use a tested Capacitor filesystem/share path only where the existing feature actually downloads a file; do not intercept ordinary links globally.
- File uploads continue to use `FormData` and input selection; native camera/file-picker integration is out of scope unless an existing screen requires it.
- Toss Payments success/failure redirects must be tested in the Capacitor WebView. If the SDK requires a browser or external-app handoff, the app must use an explicit external-navigation adapter and return through a deep link. No payment amount or authorization semantics may be changed by this task.

## Responsive verification

- Add Playwright projects or parameterized tests for widths 320, 375, and 390px, plus landscape dimensions.
- Assert no unexpected horizontal document overflow except approved scroll containers.
- Exercise representative AI chat, table-heavy settlement/admin, and admin navigation routes.
- Existing remote E2E base URL remains unchanged; tests must not assume local backend availability.
- Report actual-device limitations clearly when iOS/Android tooling is unavailable.

## Acceptance criteria

1. `npm run typecheck`, `npm run build`, and the relevant Vitest suite pass.
2. Browser login, logout, token injection, and 401 cleanup continue to work through the storage abstraction.
3. Capacitor configuration points to the Vite `dist` output and `npx cap sync` can be run when platform prerequisites are installed.
4. Native-only imports are not evaluated in browser tests/builds.
5. Mobile viewport tests cover the requested widths/orientation and the representative screens without unexplained overflow.
6. The repository documents that iOS signing/simulator verification requires macOS/Xcode and that actual Android verification requires Android tooling/device access.

## Risks and mitigations

- A community secure-storage plugin may lag the chosen Capacitor major version. Pin a compatible version and fail clearly rather than downgrade token security.
- Payment SDK behavior inside WebView may differ from browser behavior. Keep payment integration unchanged until a native smoke test proves the handoff.
- Existing remote E2E routes may require authentication or seed data. Keep layout assertions independent of sensitive backend state where possible.
