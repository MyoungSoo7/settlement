/**
 * 백엔드 표면 ↔ 화면 커버리지 게이트 — "기능은 짰는데 아무도 못 쓰는" 상태를 빌드 시점에 드러낸다.
 *
 * 왜 필요한가: `menu-route-gate` 는 메뉴↔라우트만 본다. 그래서 죽은 링크와 유령 화면은 막지만,
 * <b>백엔드에 컨트롤러가 새로 생겼는데 부르는 화면이 없는</b> 경우는 아무도 보지 않는다.
 * 실제로 card(Phase 2 완료)·insurance·deposit·organization 은 REST 와 게이트웨이 라우팅이
 * 다 있는데 화면이 0 인 채로 오래 있었다. 컴파일러도 기존 게이트도 잡지 못하는 종류의 누락이다.
 *
 * 판정 방법:
 *   백엔드 — `@RestController` 클래스의 base path(@RequestMapping, 없으면 첫 매핑 애노테이션)
 *   프론트 — frontend/src 전체(테스트 제외)의 URL 문자열 리터럴
 *   대조   — 경로변수를 와일드카드로 정규화한 뒤, 프론트 URL 이 base 와 같거나 그 하위이면 "호출됨"
 *
 * 새 컨트롤러를 추가하면 셋 중 하나를 해야 통과한다:
 *   ① 부르는 화면을 만든다  ② MACHINE_ONLY 에 등록한다  ③ SCREEN_PENDING 에 등록하고 예산을 올린다
 * ③ 은 부채를 지는 선택이라 PENDING_BUDGET 을 함께 고쳐야 해서 눈에 띈다.
 *
 * 한계: 폴리글랏 7종(Kotlin/Go/Python)은 스캔하지 않는다 — 자바 애노테이션 기준 추출이라서다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, basename } from 'node:path';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const SERVICES = [
  'order-service', 'settlement-service', 'loan-service', 'investment-service',
  'account-service', 'company-service', 'operation-service', 'market-service',
  'ai-service', 'common-data-service', 'financial-statements-service',
  'economics-service', 'organization-service', 'card-service',
  'insurance-service', 'deposit-service',
];

/**
 * 화면을 만들지 <b>않는다</b>고 선언한 표면과 사유.
 * 브라우저가 부를 수 없거나(내부 키 게이트·외부 단말 규격), 사람이 상시로 볼 대상이 아닌 것들이다.
 * 일회성 집행 경로에 화면을 두면 운영자가 상시 기능으로 오해한다.
 */
const MACHINE_ONLY = new Map([
  ['/api/ops/webhook', 'Alertmanager 가 POST 하는 수신구 — 사람이 여는 화면이 아니다'],
  ['/van/v1/authorizations', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['/van/v1/captures', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['/van/v1/refunds', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['/van/v1/voids', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['/admin/economics/sync', '수집 트리거 — X-Internal-Api-Key 게이트라 브라우저에서 못 부른다'],
  ['/admin/financial/sync', '수집 트리거 — X-Internal-Api-Key 게이트'],
  ['/admin/market/sync', '수집 트리거 — X-Internal-Api-Key 게이트'],
  ['/admin/commondata', '수집 트리거 — X-Internal-Api-Key 게이트'],
  ['/admin/backfill', '일회성 백필(계정계 정산예정 정리)'],
  ['/admin/backfill/ledger-reverse', '일회성 백필(원장 역분개 누락 정정)'],
  ['/admin/outbox/ledger', '운영 도구 — 원장 아웃박스 재발행'],
  ['/admin/payouts/backfill', '일회성 백필(지급 누락 정정)'],
  ['/admin/payouts/pii', '일회성 백필(지급 PII 암호화)'],
  ['/admin/settlement-projection', '일회성 백필(프로젝션 재적재)'],
  ['/admin/stock-reclaim', '배치 트리거 — 미결제 재고 회수'],
  ['/admin/payment-expiry', '배치 트리거 — 결제 만료 처리'],
]);

/**
 * 화면이 필요하지만 아직 없는 표면 = <b>인정된 부채</b>. 줄어들기만 해야 한다.
 * 정산 4종의 추적 위치는 docs/PLAN.md §8-8 로스터다.
 */
const SCREEN_PENDING = new Map([
  // --- order-service ---
  ['/categories', '쇼핑 카테고리 탐색 — 관리(/admin/categories)만 화면이 있다'],
  ['/admin/refunds', '환불 관리 콘솔'],
  ['/admin/pg', 'PG 라우팅 설정 콘솔'],
  ['/api/payments', '환불 이력 조회 화면'],
  ['/payments/split', '분할결제 UI'],
  ['/products/{productId}/variants', '상품 옵션(SKU) 관리 화면'],
  ['/admin/seller-tiers', '셀러 등급 관리 — 정산 수수료율과 직결된다'],
  ['/admin/shipments', '송장 일괄 업로드 화면'],
  ['/memberships', '멤버십 관리 화면'],
  // --- settlement-service (docs/PLAN.md §8-8) ---
  ['/admin/commission-rates', '수수료율 콘솔 — PLAN 8-8'],
  ['/admin/dlq', 'DLQ 재처리 콘솔 — PLAN 8-8'],
  ['/admin/event-track', '이벤트 추적 콘솔 — PLAN 8-8'],
  ['/admin/settlements/rerun', '정산 재구동 — PLAN 8-8'],
  ['/admin/settlements', '홀드백 미리보기'],
  ['/admin/seller-bank-accounts', '셀러 계좌 레지스트리 — PLAN 8-2'],
  ['/api/seller/bank-account', '셀러 본인 계좌 등록(셀러 화면)'],
  ['/api/reports', '정산 리포트(PDF) 다운로드'],
  ['/api/settlements/query', 'ES 기반 정산 검색'],
  ['/admin/seller-tax-profiles', '세무 콘솔 — PLAN 8-8'],
  ['/admin/tax/scans', '세무 콘솔 — PLAN 8-8'],
  ['/admin/tax/settlements/{settlementId}', '세무 콘솔 — PLAN 8-8'],
  ['/api/tax-invoices/scans', '세금계산서 스캔(셀러 화면)'],
  ['/api/tax-invoices/settlement/{settlementId}', '세금계산서 조회(셀러 화면)'],
  // --- loan-service ---
  ['/loans/secured', '담보대출 화면'],
  ['/loans/secured/{loanId}/collateral', '담보 감시(마진콜·청산) 화면'],
  ['/loans/leases', '리스 화면'],
  ['/loans/repayment', '상환 화면'],
  ['/loans/company-reputation', '기업 평판 조회(대출 심사 보조)'],
  // --- account-service ---
  ['/api/banking/pensions', '퇴직연금 화면'],
  ['/api/banking/savings', '적금 화면'],
  ['/api/banking/time-deposits', '예금 화면'],
  // --- company-service ---
  ['/admin/company/collect', '뉴스 수집 실행 콘솔'],
  ['/admin/company/companies', '기업 등록 관리'],
  ['/admin/company/documents', '문서함 관리'],
  ['/admin/company/reputation', '평판 스코어 관리'],
  ['/admin/company/sellers', '셀러↔기업 연결 관리'],
  ['/admin/company/workforce', '고용현황 임포트'],
  ['/api/company/workforce', '고용현황 조회 — CEO 화면은 다른 경로를 쓴다'],
  // --- 그 외 서비스 ---
  ['/api/ai/knowledge', 'AI 지식베이스 관리'],
  ['/api/common-data/sources', '공공데이터 데이터소스 등록 화면'],
  ['/api/organizations', '조직·멤버십 화면 — 서비스 전체가 화면 0'],
  ['/api/cards', '법인카드 화면 — Phase 2 까지 구현됐는데 화면 0'],
  ['/api/insurance', '보험 상품설명서 — 서비스 전체가 화면 0'],
  ['/api/insurance/applications', '보험 청약 화면'],
  ['/api/insurance/policies', '보험 계약 화면'],
  ['/api/insurance/proposals', '보험 가입설계 화면'],
  ['/admin/deposits', '예치금 수기 콘솔'],
  ['/api/deposits', '예치금 조회 화면'],
]);

/**
 * 미노출 부채의 상한. <b>내려가기만 한다</b> — 화면을 붙였으면 이 수를 함께 내린다.
 * 올리려면 그 자체가 리뷰 대상이라는 뜻이다.
 */
const PENDING_BUDGET = 48;

const read = (path) => readFileSync(path, 'utf8');

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

/** `${id}`(프론트 템플릿)와 `{id}`(스프링 경로변수)를 같은 와일드카드로 접는다. */
const norm = (path) => path.replace(/\$?\{[^}]*\}/g, '*').replace(/\/+$/, '');

/** 프론트가 실제로 부르는 URL — api/ 모듈뿐 아니라 페이지가 api.get() 을 직접 부르는 곳도 있다. */
function frontendUrls() {
  const urls = new Set();
  for (const file of walk(join(REPO_ROOT, 'frontend', 'src'))) {
    if (!/\.(ts|tsx)$/.test(file) || file.includes('__tests__')) continue;
    for (const m of read(file).matchAll(/['"`](\/[a-zA-Z0-9/{}$_.*-]+)['"`]/g)) urls.add(norm(m[1]));
  }
  return urls;
}

/** 자바 서비스의 REST 컨트롤러 base path 목록. 내부 전용(/internal·/actuator)은 대상이 아니다. */
function controllers() {
  const found = [];
  for (const service of SERVICES) {
    for (const file of walk(join(REPO_ROOT, service, 'src', 'main', 'java'))) {
      if (!file.endsWith('.java')) continue;
      const src = read(file);
      if (!src.includes('@RestController')) continue;
      const base = src.match(/@RequestMapping\(\s*"([^"]+)"/)?.[1]
        ?? src.match(/@(?:Get|Post|Put|Patch|Delete)Mapping\(\s*"([^"]+)"/)?.[1];
      if (!base || base.startsWith('/internal') || base.startsWith('/actuator')) continue;
      found.push({ service, cls: basename(file, '.java'), base });
    }
  }
  return found;
}

const isCalled = (base, urls) => {
  const b = norm(base);
  for (const url of urls) {
    if (url === b || url.startsWith(b + '/')) return true;
  }
  return false;
};

const sorted = (values) => [...new Set(values)].sort();

test('화면이 부르지 않는 컨트롤러는 전부 사유와 함께 분류돼 있다', () => {
  const urls = frontendUrls();
  const unclassified = sorted(controllers()
    .filter((c) => !isCalled(c.base, urls))
    .filter((c) => !MACHINE_ONLY.has(c.base) && !SCREEN_PENDING.has(c.base))
    .map((c) => `${c.base} (${c.service}/${c.cls})`));

  assert.deepEqual(unclassified, [],
    `프론트가 부르지 않는 컨트롤러가 분류되지 않았습니다:\n  ${unclassified.join('\n  ')}\n`
    + '부르는 화면을 만들거나, scripts/harness/test/api-screen-gate.test.mjs 의 '
    + 'MACHINE_ONLY(기계 전용) 또는 SCREEN_PENDING(화면 부채, PENDING_BUDGET 도 함께 상향)에 '
    + '사유와 함께 등록하세요.');
});

test('분류 목록에 이미 사라진 컨트롤러가 남아 있지 않다', () => {
  const bases = new Set(controllers().map((c) => c.base));
  const stale = sorted([...MACHINE_ONLY.keys(), ...SCREEN_PENDING.keys()].filter((b) => !bases.has(b)));

  assert.deepEqual(stale, [],
    `이미 없는 컨트롤러가 분류 목록에 남아 있습니다: ${stale.join(', ')}`);
});

test('화면이 생긴 표면은 부채 목록에서 내려간다', () => {
  const urls = frontendUrls();
  const done = sorted([...SCREEN_PENDING.keys()].filter((base) => isCalled(base, urls)));

  assert.deepEqual(done, [],
    `화면이 생겼는데 SCREEN_PENDING 에 남아 있습니다: ${done.join(', ')}\n`
    + `목록에서 지우고 PENDING_BUDGET 을 ${PENDING_BUDGET - done.length} 로 내리세요.`);
});

test('기계 전용으로 선언한 표면을 화면이 부르지 않는다', () => {
  const urls = frontendUrls();
  const called = sorted([...MACHINE_ONLY.keys()].filter((base) => isCalled(base, urls)));

  assert.deepEqual(called, [],
    `기계 전용으로 분류된 표면을 프론트가 부르고 있습니다: ${called.join(', ')}\n`
    + '분류가 틀렸거나(→ 목록에서 제거), 브라우저가 부르면 안 되는 것을 부르고 있습니다(→ 화면 수정).');
});

test('미노출 부채가 늘지 않았다 (예산은 내려가기만 한다)', () => {
  assert.ok(SCREEN_PENDING.size <= PENDING_BUDGET,
    `화면 부채가 예산을 넘었습니다: ${SCREEN_PENDING.size} > ${PENDING_BUDGET}. `
    + '새 백엔드 기능에는 화면을 함께 붙이는 것이 기본이며, 미루려면 PENDING_BUDGET 상향이 리뷰 대상입니다.');
  assert.equal(SCREEN_PENDING.size, PENDING_BUDGET,
    `부채가 줄었는데 예산이 그대로입니다: ${SCREEN_PENDING.size} < ${PENDING_BUDGET}. `
    + `PENDING_BUDGET 을 ${SCREEN_PENDING.size} 로 내려 래칫을 조이세요.`);
});

test('추출기가 살아 있다 (스캔이 비면 판정 전체가 거짓이 된다)', () => {
  // 추출 정규식이 깨져 URL 0개·컨트롤러 0개가 되면 위 테스트들은 조용히 전부 통과한다.
  assert.ok(controllers().length >= 100, '컨트롤러 스캔 결과가 비정상적으로 적습니다.');
  assert.ok(frontendUrls().size >= 150, '프론트 URL 스캔 결과가 비정상적으로 적습니다.');
});
