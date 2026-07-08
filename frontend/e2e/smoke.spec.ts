import { test, expect } from '@playwright/test';

test.describe('운영 smoke — 라우팅/백엔드 프록시 통합', () => {
  test('루트 / 200 + 로그인 페이지 진입 가능', async ({ page }) => {
    const response = await page.goto('/');
    expect(response?.status(), '/ 응답 코드').toBeLessThan(400);
    await page.goto('/login');
    await expect(page.getByRole('button', { name: '👤 일반 사용자' })).toBeVisible();
  });

  test('/actuator/health → 200 (nginx → 백엔드 프록시 정상)', async ({ request }) => {
    const res = await request.get('/actuator/health');
    expect(res.status(), '/actuator/health 200 — nginx upstream + 백엔드 헬스').toBe(200);
    const body = await res.json();
    expect(body.status, '백엔드 health.status=UP').toBe('UP');
  });

  test('/auth/dev/auto-login?role=USER POST → 200 (백엔드 도달)', async ({ request }) => {
    const res = await request.post('/auth/dev/auto-login?role=USER');
    expect(
      res.status(),
      'POST /auth/dev/auto-login — 인증 끊김(401/403)/프록시 끊김(502/404) 사전 차단',
    ).toBe(200);
  });
});

// INC-2026-0708 재발 방지 — 상단 메뉴가 부르는 API 가 "존재하고 도달 가능한지" 를
// 배포 직후 운영 도메인에서 검증한다. 200(공개) / 401·403(인증요구) 은 정상,
// 500(미배포·라우팅 누락·NoResourceFound) 과 502/504(업스트림 끊김) 만 실패로 본다.
test.describe('운영 smoke — 전 메뉴 API 도달성 (INC-2026-0708)', () => {
  const menuApis: Array<[string, string]> = [
    ['정산 검색 (settlement-query)', '/api/settlements/search?page=0&size=1'],
    ['경제지표 (economics)', '/api/economics/indicators'],
    ['재무제표 기업 (financial)', '/api/financial/companies?page=0&size=1'],
    ['기업조회 (company)', '/api/company/companies?page=0&size=1'],
    ['AI 대화 (ai)', '/api/ai/conversations?page=0&size=1'],
    ['관리자 메뉴 (monolith)', '/admin/menus/flat'],
  ];

  for (const [name, path] of menuApis) {
    test(`${name} — 5xx 아님`, async ({ request }) => {
      const res = await request.get(path);
      expect(
        res.status(),
        `${path} — 500=미배포/계약불일치, 502/504=업스트림 끊김 (200/401/403 은 정상)`,
      ).toBeLessThan(500);
    });
  }
});
