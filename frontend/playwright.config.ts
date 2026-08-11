import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'https://jen.lemuel.co.kr';
const isCI = !!process.env.CI;

/**
 * loan.spec.ts 는 **Chromium 전용 API** 에 의존하므로 크로스 브라우저 프로젝트에서 제외한다.
 *  - `page.context().newCDPSession(page)` — CDP 는 Chromium 만 지원(firefox/webkit 에서 throw).
 *  - `test.use({ serviceWorkers: 'block' })` — 이 컨텍스트 옵션은 Chromium 에서만 유효.
 *    Firefox 는 SW 가 살아 있어 `page.route` 가 XHR 을 못 잡고 mock 이 통째로 무력화된다.
 * 크로스 브라우저·모바일 검증은 smoke(라우팅/프록시) + auto-login(인증 플로우)이 담당한다.
 */
const CHROMIUM_ONLY = ['**/loan.spec.ts'];

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  // 로컬 기본값(=CPU 코어 수, 실측 10워커)에서 WebKit 워커가 종료되지 않고 300초 뒤 force-kill 되는
  // 현상이 간헐 발생한다(테스트는 전부 통과해도 종료 코드 1). 4워커 이하에서는 재현되지 않아 상한을 둔다.
  workers: isCI ? 2 : 4,
  reporter: isCI ? [['list'], ['html', { open: 'never' }], ['github']] : 'list',
  timeout: 30_000,
  expect: { timeout: 7_000 },

  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    // 원격 운영 URL 을 5개 엔진이 동시에 때리므로 15s 는 빠듯하다 — webkit 워커 4개 병렬에서
    // `page.goto('/login')` 이 15s 를 넘겨 실패한 실측(2026-08-11)에 맞춰 상향.
    navigationTimeout: 30_000,
  },

  projects: [
    // 기준 프로젝트 — 전체 스펙 실행(loan.spec.ts 포함).
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    // 크로스 브라우저 — 렌더링 엔진(Gecko / WebKit) 차이 검증.
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
      testIgnore: CHROMIUM_ONLY,
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      testIgnore: CHROMIUM_ONLY,
    },
    // 모바일 — 뷰포트(393/390px)·터치·devicePixelRatio 까지 포함한 반응형 검증.
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 7'] },
      testIgnore: CHROMIUM_ONLY,
    },
    {
      name: 'mobile-safari',
      use: { ...devices['iPhone 14'] },
      testIgnore: CHROMIUM_ONLY,
    },
  ],
});
