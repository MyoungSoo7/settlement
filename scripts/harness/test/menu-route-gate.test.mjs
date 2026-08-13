/**
 * 메뉴 정합 게이트 — 메뉴 트리의 세 사본이 서로 어긋나지 않는지 빌드 시점에 대조한다.
 *
 *   ① 시드 SQL (정본)  order-service/.../V20260813100000__menu_area_permission.sql
 *   ② 프론트 폴백      frontend/src/data/menuFallback.ts
 *   ③ 라우트           frontend/src/App.tsx
 *
 * 왜 필요한가: 메뉴는 "보여 줄 곳", 라우트는 "갈 수 있는 곳"이다. 둘은 다른 파일에 살기 때문에
 * 한쪽만 바꾸면 아무도 모르게 죽은 링크(메뉴는 있는데 라우트 없음)나 유령 화면(라우트는 있는데
 * 진입점 없음)이 생긴다. 컴파일러가 잡아 주지 않는 종류의 드리프트라 게이트가 대신 잡는다.
 *
 * 새 화면을 붙이는 절차는 두 스텝으로 고정된다:
 *   1) App.tsx 에 라우트 추가   2) 시드 SQL + 폴백에 메뉴 행 추가
 * 메뉴에 넣지 않을 화면이라면 아래 ROUTES_WITHOUT_MENU 에 사유와 함께 등록한다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const SEED_SQL = join(REPO_ROOT, 'order-service', 'src', 'main', 'resources', 'db',
  'migration', 'V20260813100000__menu_area_permission.sql');
const FALLBACK_TS = join(REPO_ROOT, 'frontend', 'src', 'data', 'menuFallback.ts');
const APP_TSX = join(REPO_ROOT, 'frontend', 'src', 'App.tsx');

/**
 * 메뉴에 넣지 않는 라우트와 그 사유.
 * 여기에 등록하는 것은 "진입점이 네비게이션이 아니다"라는 명시적 선언이다.
 */
const ROUTES_WITHOUT_MENU = new Map([
  ['/', '로그인으로 리다이렉트'],
  ['/login', '공개 — 로그인'],
  ['/admin/login', '공개 — 관리자 로그인'],
  ['/register', '공개 — 회원가입'],
  ['/forgot-password', '공개 — 비밀번호 찾기'],
  ['/reset-password', '공개 — 비밀번호 재설정'],
  ['/order/toss/fail', 'PG 콜백'],
  ['/order/toss/success', 'PG 콜백'],
  ['/financials', '공개 조회 — CEO 메뉴(/admin/ceo/financials)로 진입'],
  ['/companies', '공개 조회 — CEO 메뉴로 진입'],
  ['/economics', '공개 조회 — CEO 메뉴로 진입'],
  ['/workforce', '공개 조회 — CEO 메뉴로 진입'],
  ['/loans', 'CEO 메뉴(/admin/ceo/loans)로 진입'],
  ['/cart', '헤더 장바구니 아이콘으로 진입'],
  ['/mypage', '헤더 MY 버튼으로 진입'],
  ['/categories', '관리 화면 내부 이동'],
  ['/tags', '관리 화면 내부 이동'],
  ['/print/settlement/:id', '인쇄 전용 — 새 창으로 열린다'],
  ['/admin/system', '그룹 진입 리다이렉트'],
  ['/admin/ceo', '그룹 진입 리다이렉트'],
  ['/admin/operation', '구 경로 리다이렉트'],
]);

const read = (path) => readFileSync(path, 'utf8');

/** 시드 SQL 의 INSERT 구간에서만 경로 리터럴을 뽑는다 (DELETE 절의 경로는 제외). */
function seedPaths() {
  const sql = read(SEED_SQL)
    .split('\n')
    .filter((line) => !/^\s*DELETE\s+FROM/i.test(line))
    .join('\n');
  return [...sql.matchAll(/'(\/[^']*)'/g)].map((m) => m[1]);
}

function fallbackPaths() {
  return [...read(FALLBACK_TS).matchAll(/path:\s*'(\/[^']*)'/g)].map((m) => m[1]);
}

function routePaths() {
  return [...read(APP_TSX).matchAll(/<Route\s+path="([^"]+)"/g)].map((m) => m[1]);
}

const sorted = (values) => [...new Set(values)].sort();

test('시드 SQL 과 프론트 폴백의 메뉴 경로가 같다', () => {
  assert.deepEqual(sorted(fallbackPaths()), sorted(seedPaths()),
    'menuFallback.ts 가 시드 SQL 과 어긋납니다. 정본은 SQL 이며 폴백은 그 사본입니다.');
});

test('시드 SQL 과 프론트 폴백의 메뉴 개수가 같다', () => {
  // 시드된 모든 행은 링크(GROUP/ITEM)라 행마다 경로가 정확히 하나 있다.
  assert.equal(fallbackPaths().length, seedPaths().length,
    '메뉴 행 수가 다릅니다 — 한쪽에만 항목을 추가했을 가능성이 큽니다.');
});

test('모든 메뉴 경로에 대응하는 라우트가 있다 (죽은 링크 없음)', () => {
  const routes = new Set(routePaths());
  const dead = sorted(seedPaths()).filter((path) => !routes.has(path));

  assert.deepEqual(dead, [],
    `메뉴는 있는데 App.tsx 에 라우트가 없습니다: ${dead.join(', ')}`);
});

test('메뉴에 없는 라우트는 사유와 함께 등록돼 있다 (유령 화면 없음)', () => {
  const menu = new Set(seedPaths());
  const unlisted = sorted(routePaths())
    .filter((path) => !menu.has(path) && !ROUTES_WITHOUT_MENU.has(path));

  assert.deepEqual(unlisted, [],
    `라우트는 있는데 진입할 메뉴가 없습니다: ${unlisted.join(', ')}\n`
    + '메뉴 행을 추가하거나, 네비게이션 진입점이 아니라면 '
    + 'scripts/harness/test/menu-route-gate.test.mjs 의 ROUTES_WITHOUT_MENU 에 사유와 함께 등록하세요.');
});

test('메뉴 없는 라우트 allowlist 에 죽은 항목이 없다', () => {
  const routes = new Set(routePaths());
  const stale = [...ROUTES_WITHOUT_MENU.keys()].filter((path) => !routes.has(path));

  assert.deepEqual(stale, [],
    `이미 사라진 라우트가 allowlist 에 남아 있습니다: ${stale.join(', ')}`);
});

test('삭제된 사이드바 셸 3종이 되살아나지 않았다', () => {
  // 트리 기반 셸(SideNavLayout)로 합쳤다. 파일이 다시 생기면 하드코딩 네비가 부활한 것이다.
  const app = read(APP_TSX);
  for (const gone of ['SettlementLayout', 'CeoLayout', 'SystemLayout']) {
    assert.ok(!new RegExp(`<${gone}[\\s>]`).test(app),
      `${gone} 이 App.tsx 에서 다시 쓰이고 있습니다 — 네비게이션 정본은 menus 테이블입니다.`);
  }
});
