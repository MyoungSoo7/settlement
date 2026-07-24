import { test, expect, type Route } from '@playwright/test';

/**
 * 대출 화면 신규 UI 동작 검증 — 로컬 dev 서버 + route mock.
 *
 * dev 는 /loans 프록시가 없고 위성 데이터가 붙지 않으므로(프로젝트 관례), 토큰을 localStorage 에 주입하고
 * 백엔드 응답을 mock 해 화면 렌더·상호작용만 결정적으로 검증한다. 실제 인가는 백엔드가 강제.
 *
 * ⚠️ Playwright 의 glob/정규식/술어 라우트는 쿼리스트링 URL(예: /loans?sellerId=1)을 신뢰성 있게 매칭하지
 * 못한다(SPA 경로 /loans 와 API 경로 /loans 충돌). 그래서 '**\/*' 단일 catch-all 로 모든 요청을 가로채
 * URL 로 분기한다 — 매칭이 확정적이고 브라우저 캐시도 우회된다.
 *
 * 실행: 로컬 vite(`npm run dev`, 3000) 기동 후 PLAYWRIGHT_BASE_URL=http://localhost:3000 로 이 스펙만 실행.
 */

const seedAdmin = () => {
  localStorage.setItem('access_token', 'e2e-dev-token');
  localStorage.setItem('user_email', 'admin@example.com');
  localStorage.setItem('user_role', 'ADMIN');
  localStorage.setItem('login_timestamp', '2026-07-24T09:00:00.000Z');
};

const json = (route: Route, body: unknown) =>
  route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });

// 서비스워커가 XHR 을 가로채면 page.route 가 무력화된다(문서 요청만 잡히고 fetch 는 SW 로 흐름).
// SW 를 차단해 모든 요청이 네트워크(→ page.route)로 흐르게 한다.
test.use({ serviceWorkers: 'block' });

test.describe('대출 화면 — 신규 UI (만기 / 연체·상각 / 기업 상환)', () => {
  test.beforeEach(async ({ page }) => {
    // HTTP 캐시 비활성화 — dev 서버가 /loans?sellerId= 를 index.html(200)로 응답하면 브라우저가 캐시하고,
    // 캐시 히트는 page.route 가 가로채지 못한다(요청 이벤트만 발생). CDP 로 캐시를 꺼 모든 요청을 네트워크로 강제.
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
    await page.addInitScript(seedAdmin);
  });

  test('선정산 목록: 만기 컬럼 + ADMIN 연체/상각 버튼 + 연체 실행', async ({ page }) => {
    let overdueCalled = false;
    await page.route('**/*', async (route) => {
      const url = route.request().url();
      if (url.includes('/loans/1/overdue')) {
        overdueCalled = true;
        return json(route, { id: 1, sellerId: 1, principal: 800000, fee: 800, outstanding: 800800, status: 'OVERDUE', dueAt: '2026-07-17T09:00:00' });
      }
      if (url.includes('/loans') && url.includes('sellerId')) {
        return json(route, [
          { id: 1, sellerId: 1, principal: 800000, fee: 800, outstanding: 800800, status: 'DISBURSED', disbursedAt: '2026-07-10T09:00:00', dueAt: '2026-07-17T09:00:00' },
          { id: 2, sellerId: 1, principal: 500000, fee: 500, outstanding: 500500, status: 'OVERDUE', disbursedAt: '2026-06-01T09:00:00', dueAt: '2026-06-08T09:00:00' },
        ]);
      }
      return route.continue();
    });

    await page.goto('/loans');
    await page.getByRole('button', { name: '조회' }).click();

    await expect(page.getByRole('columnheader', { name: '만기' })).toBeVisible();
    await expect(page.getByRole('cell', { name: /2026/ }).first()).toBeVisible();
    await expect(page.getByText('OVERDUE')).toBeVisible();
    await expect(page.getByRole('button', { name: '연체' })).toBeVisible();
    await expect(page.getByRole('button', { name: '상각' })).toBeVisible();

    await page.getByRole('button', { name: '연체' }).click();
    await expect.poll(() => overdueCalled, { timeout: 5000 }).toBe(true);
    await expect(page.getByText(/연체 처리 완료/)).toBeVisible();

    await page.screenshot({ path: 'e2e/__screenshots__/loan-seller.png', fullPage: true });
  });

  test('기업대출: DISBURSED 에 상환 버튼 + 상환 실행(금액 전달)', async ({ page }) => {
    let repayAmount: number | null = null;
    await page.route('**/*', async (route) => {
      const req = route.request();
      const url = req.url();
      if (url.includes('/loans/corporate/5001/repay')) {
        repayAmount = req.postDataJSON()?.amount ?? null;
        return json(route, { id: 5001, stockCode: '005930', corpName: '삼성전자', principal: 1000000, fee: 6000, outstanding: 406000, termDays: 30, creditScore: 82, creditGrade: 'A', status: 'DISBURSED' });
      }
      if (url.includes('/loans/corporate/credit/005930')) {
        return json(route, { stockCode: '005930', corpName: '삼성전자', market: 'KOSPI', fiscalYear: 2025, creditScore: 82, creditGrade: 'A', limit: 5000000, debtRatio: 40.2, operatingMargin: 15.5, roa: 8.1, reputationGrade: 'B' });
      }
      if (url.includes('/loans/corporate') && url.includes('stockCode=005930')) {
        return json(route, [{ id: 5001, stockCode: '005930', corpName: '삼성전자', principal: 1000000, fee: 6000, outstanding: 1006000, termDays: 30, creditScore: 82, creditGrade: 'A', status: 'DISBURSED' }]);
      }
      if (url.includes('/api/financial/companies')) {
        return json(route, { content: [{ stockCode: '005930', name: '삼성전자', market: 'KOSPI' }], totalElements: 1, totalPages: 1, number: 0, size: 10 });
      }
      return route.continue();
    });

    await page.goto('/loans');
    await page.getByRole('button', { name: '기업대출' }).click();
    await page.getByPlaceholder('기업명 또는 종목코드').fill('삼성');
    await page.getByRole('button', { name: '검색' }).click();
    await page.getByRole('button', { name: /삼성전자/ }).click();

    const repayBtn = page.getByRole('button', { name: '상환' });
    await expect(repayBtn).toBeVisible();
    await repayBtn.click();
    await page.getByRole('button', { name: '확인' }).click();
    await expect.poll(() => repayAmount, { timeout: 5000 }).toBe(1006000);
    await expect(page.getByText(/기업대출 상환 완료/)).toBeVisible();

    await page.screenshot({ path: 'e2e/__screenshots__/loan-corporate.png', fullPage: true });
  });
});
