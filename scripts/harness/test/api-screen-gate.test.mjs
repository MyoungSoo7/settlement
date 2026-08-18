/**
 * 백엔드 표면 ↔ 화면 커버리지 게이트 — "기능은 짰는데 아무도 못 쓰는" 상태를 빌드 시점에 드러낸다.
 *
 * 왜 필요한가: `menu-route-gate` 는 메뉴↔라우트만 본다. 그래서 죽은 링크와 유령 화면은 막지만,
 * <b>백엔드에 컨트롤러가 새로 생겼는데 부르는 화면이 없는</b> 경우는 아무도 보지 않는다.
 * 실제로 card(Phase 2 완료)·insurance·deposit·organization 은 REST 와 게이트웨이 라우팅이
 * 다 있는데 화면이 0 인 채로 오래 있었다. 컴파일러도 기존 게이트도 잡지 못하는 종류의 누락이다.
 *
 * 판정 방법:
 *   백엔드 — `@RestController` 의 <b>엔드포인트 전체</b>(클래스 @RequestMapping + 메서드 매핑 조합).
 *            클래스 매핑 없이 메서드에 전체 경로를 다는 컨트롤러가 실제로 있어서
 *            (ApplicationDocumentController) "첫 경로 = base" 모델로는 나머지 경로를 놓친다.
 *   프론트 — frontend/src 전체(테스트 제외)의 URL 문자열 리터럴.
 *   귀속   — URL 을 <b>최장일치</b> 엔드포인트에만 크레딧한다. 접두사만 보면 형제 컨트롤러가
 *            남의 호출을 가로챈다 — `/admin/deposits/proofs`(DepositProofAdminController) 호출이
 *            `/admin/deposits`(DepositAdminController)까지 "덮였다"로 만들어 가짜 GREEN 이 된다.
 *
 * 그래서 목록의 키는 경로가 아니라 <b>`서비스/클래스명`</b>이다 — 두 컨트롤러가 같은 base 를
 * 공유할 수 있어 경로 키는 애초에 유일하지 않다.
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
 * 화면을 만들지 <b>않는다</b>고 선언한 컨트롤러와 사유.
 * 브라우저가 부를 수 없거나(내부 키 게이트·외부 단말 규격), 사람이 상시로 볼 대상이 아닌 것들이다.
 * 일회성 집행 경로에 화면을 두면 운영자가 상시 기능으로 오해한다.
 */
const MACHINE_ONLY = new Map([
  ['operation-service/AlertmanagerWebhookController', 'Alertmanager 가 POST 하는 수신구 — 사람이 여는 화면이 아니다'],
  ['card-service/AuthorizationVanAdapter', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['card-service/CaptureVanAdapter', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['card-service/RefundVanAdapter', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['card-service/VoidVanAdapter', '카드 VAN 단말 규격(외부 시스템 진입점)'],
  ['economics-service/EconomicsSyncAdminController', '수집 트리거 — X-Internal-Api-Key 게이트라 브라우저에서 못 부른다'],
  ['financial-statements-service/FinancialSyncAdminController', '수집 트리거 — X-Internal-Api-Key 게이트'],
  ['market-service/MarketSyncAdminController', '수집 트리거 — X-Internal-Api-Key 게이트'],
  ['common-data-service/CommonDataAdminController', '수집 트리거 — X-Internal-Api-Key 게이트'],
  ['account-service/SettlementScheduledClearingAdminController', '일회성 백필(정산예정 정리)'],
  ['settlement-service/LedgerReverseBackfillAdminController', '일회성 백필(원장 역분개 누락 정정)'],
  ['settlement-service/LedgerOutboxAdminController', '운영 도구 — 원장 아웃박스 재발행'],
  ['settlement-service/PayoutBackfillAdminController', '일회성 백필(지급 누락 정정)'],
  ['settlement-service/PayoutPiiBackfillAdminController', '일회성 백필(지급 PII 암호화)'],
  ['order-service/SettlementProjectionBackfillController', '일회성 백필(프로젝션 재적재)'],
  ['order-service/AdminStockReclaimController', '배치 트리거 — 미결제 재고 회수'],
  ['order-service/AdminPaymentExpiryController', '배치 트리거 — 결제 만료 처리'],
]);

/**
 * 화면이 필요하지만 아직 없는 컨트롤러 = <b>인정된 부채</b>. 줄어들기만 해야 한다.
 * 정산 4종의 추적 위치는 docs/PLAN.md §8-8 로스터다.
 */
const SCREEN_PENDING = new Map([
  // --- order-service ---
  ['order-service/PublicEcommerceCategoryController', '쇼핑 카테고리 탐색 — 관리 화면만 있다'],
  ['order-service/AdminRefundController', '환불 관리 콘솔'],
  ['order-service/PgRoutingController', 'PG 라우팅 설정 콘솔'],
  ['order-service/RefundHistoryController', '환불 이력 조회 화면'],
  ['order-service/SplitPaymentController', '분할결제 UI'],
  ['order-service/ProductVariantController', '상품 옵션(SKU) 관리 화면'],
  ['order-service/AdminSellerTierController', '셀러 등급 관리 — 정산 수수료율과 직결된다'],
  // 포인트 원장 Phase 1 은 백엔드까지다 — 잔액 조회·수기 지급 화면은 Phase 3.
  ['order-service/PointController', '내 포인트 잔액 화면 — 포인트 원장 Phase 3'],
  ['order-service/AdminPointController', '포인트 수기 지급·소멸 콘솔 — 포인트 원장 Phase 3'],
  // 기프트카드도 백엔드까지다 — 발행 콘솔과 코드 등록 화면은 후속.
  ['order-service/GiftCardController', '기프트카드 등록·잔액 화면 — 기프트카드 원장 후속'],
  ['order-service/AdminGiftCardController', '기프트카드 발행·소멸 콘솔 — 기프트카드 원장 후속'],
  ['order-service/AdminTrackingUploadController', '송장 일괄 업로드 화면'],
  ['order-service/MembershipController', '멤버십 관리 화면'],
  // --- settlement-service (docs/PLAN.md §8-8) ---
  ['settlement-service/EventTrackAdminController', '이벤트 추적 콘솔 — PLAN 8-8'],
  ['settlement-service/SettlementRerunAdminController', '정산 재구동 — PLAN 8-8'],
  ['settlement-service/HoldbackPreviewAdminController', '홀드백 미리보기'],
  ['settlement-service/SellerBankAccountAdminController', '셀러 계좌 레지스트리 — PLAN 8-2'],
  ['settlement-service/SellerBankAccountSelfController', '셀러 본인 계좌 등록(셀러 화면)'],
  ['settlement-service/ReportController', '정산 리포트(PDF) 다운로드'],
  ['settlement-service/SettlementQueryController', 'ES 기반 정산 검색'],
  // 관리자 세무 3종은 /admin/settlement/tax 콘솔로 노출됐다(2026-08-14). 아래 둘은 셀러 화면 몫이라 남는다.
  ['settlement-service/TaxInvoiceScanController', '세금계산서 스캔(셀러 화면)'],
  ['settlement-service/TaxInvoiceSellerController', '세금계산서 조회(셀러 화면)'],
  // --- loan-service ---
  ['loan-service/SecuredLoanController', '담보대출 화면 — 서류 리뷰 큐만 화면이 있다'],
  ['loan-service/CollateralController', '담보 감시(재평가·마진콜·청산) 화면'],
  ['loan-service/LeaseController', '리스 화면'],
  ['loan-service/RepaymentController', '상환 화면'],
  ['loan-service/CompanyReputationController', '기업 평판 조회(대출 심사 보조)'],
  // --- account-service ---
  ['account-service/RetirementPensionController', '퇴직연금 화면'],
  ['account-service/InstallmentSavingsController', '적금 화면'],
  ['account-service/TimeDepositController', '예금 화면'],
  // --- company-service ---
  ['company-service/CompanyCollectAdminController', '뉴스 수집 실행 콘솔'],
  ['company-service/CompanyRegistrationAdminController', '기업 등록 관리'],
  ['company-service/CompanyDocumentAdminController', '문서함 관리'],
  ['company-service/ReputationAdminController', '평판 스코어 관리'],
  ['company-service/SellerLinkAdminController', '셀러↔기업 연결 관리'],
  ['company-service/CompanyWorkforceImportAdminController', '고용현황 임포트'],
  ['company-service/CompanyWorkforceController', '고용현황 조회 — CEO 화면은 다른 경로를 쓴다'],
  // --- insurance-service ---
  ['insurance-service/InsuranceApplicationController', '보험 청약 화면 — 서류 리뷰 큐만 화면이 있다'],
  ['insurance-service/PolicyController', '보험 계약 화면'],
  ['insurance-service/ProposalController', '보험 가입설계 화면'],
  ['insurance-service/ProductDisclosureController', '상품설명서 교부 증빙 화면(완전판매 게이트)'],
  // --- deposit-service ---
  ['deposit-service/DepositAdminController', '예치금 수기 콘솔 — 증빙 리뷰 큐만 화면이 있다'],
  ['deposit-service/DepositController', '예치금 조회 화면'],
  // --- 그 외 ---
  ['investment-service/RecommendationAdminController', '추천 스크리닝 운영 화면'],
  ['ai-service/KnowledgeController', 'AI 지식베이스 관리'],
  ['common-data-service/DataSourceController', '공공데이터 데이터소스 등록 화면'],
  ['organization-service/OrganizationController', '조직·멤버십 화면 — 서비스 전체가 화면 0'],
]);

/**
 * 미노출 부채의 상한. <b>내려가기만 한다</b> — 화면을 붙였으면 이 수를 함께 내린다.
 * 올리려면 그 자체가 리뷰 대상이라는 뜻이다.
 */
const PENDING_BUDGET = 47;

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
const norm = (path) => path.replace(/\$?\{[^}]*\}/g, '*').replace(/\/+$/, '') || '/';

/** 프론트가 실제로 부르는 URL — api/ 모듈뿐 아니라 페이지가 api.get() 을 직접 부르는 곳도 있다. */
function frontendUrls() {
  const urls = new Set();
  for (const file of walk(join(REPO_ROOT, 'frontend', 'src'))) {
    if (!/\.(ts|tsx)$/.test(file) || file.includes('__tests__')) continue;
    for (const m of read(file).matchAll(/['"`](\/[a-zA-Z0-9/{}$_.*-]+)['"`]/g)) urls.add(norm(m[1]));
  }
  return urls;
}

/**
 * 자바 서비스의 REST 컨트롤러와 그 엔드포인트 전체.
 * 메서드 경로가 이미 클래스 base 로 시작하면(= 절대 경로 표기) 덧붙이지 않는다.
 */
function controllers() {
  const found = [];
  for (const service of SERVICES) {
    for (const file of walk(join(REPO_ROOT, service, 'src', 'main', 'java'))) {
      if (!file.endsWith('.java')) continue;
      const src = read(file);
      if (!src.includes('@RestController')) continue;

      const classBase = src.match(/@RequestMapping\(\s*"([^"]+)"/)?.[1] ?? '';
      const methodPaths = [...src.matchAll(/@(?:Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)]
        .map((m) => m[1]);
      // 경로 없는 매핑(@GetMapping)은 클래스 base 그 자체를 가리킨다.
      const bareMapping = /@(?:Get|Post|Put|Patch|Delete)Mapping\s*(?:\(\s*\))?\s*[\r\n]/.test(src);

      const paths = new Set();
      if (classBase && (bareMapping || methodPaths.length === 0)) paths.add(norm(classBase));
      for (const mp of methodPaths) {
        if (!mp) { if (classBase) paths.add(norm(classBase)); continue; }
        paths.add(norm(classBase && !mp.startsWith(classBase) ? classBase + mp : mp));
      }

      const endpoints = [...paths].filter((p) => p !== '/' && !p.startsWith('/internal') && !p.startsWith('/actuator'));
      if (endpoints.length) found.push({ key: `${service}/${basename(file, '.java')}`, endpoints });
    }
  }
  return found;
}

/** 각 URL 을 최장일치 엔드포인트 하나에만 크레딧한다. */
function calledControllers() {
  const all = controllers();
  const flat = all.flatMap((c) => c.endpoints.map((p) => ({ p, key: c.key })));
  const credited = new Set();
  for (const url of frontendUrls()) {
    let best = null;
    for (const cand of flat) {
      if ((url === cand.p || url.startsWith(cand.p + '/')) && (!best || cand.p.length > best.p.length)) best = cand;
    }
    if (best) credited.add(best.key);
  }
  return credited;
}

const sorted = (values) => [...new Set(values)].sort();

test('화면이 부르지 않는 컨트롤러는 전부 사유와 함께 분류돼 있다', () => {
  const called = calledControllers();
  const unclassified = sorted(controllers()
    .map((c) => c.key)
    .filter((key) => !called.has(key) && !MACHINE_ONLY.has(key) && !SCREEN_PENDING.has(key)));

  assert.deepEqual(unclassified, [],
    `프론트가 부르지 않는 컨트롤러가 분류되지 않았습니다:\n  ${unclassified.join('\n  ')}\n`
    + '부르는 화면을 만들거나, scripts/harness/test/api-screen-gate.test.mjs 의 '
    + 'MACHINE_ONLY(기계 전용) 또는 SCREEN_PENDING(화면 부채, PENDING_BUDGET 도 함께 상향)에 '
    + '사유와 함께 등록하세요.');
});

test('분류 목록에 이미 사라진 컨트롤러가 남아 있지 않다', () => {
  const keys = new Set(controllers().map((c) => c.key));
  const stale = sorted([...MACHINE_ONLY.keys(), ...SCREEN_PENDING.keys()].filter((key) => !keys.has(key)));

  assert.deepEqual(stale, [],
    `이미 없는(또는 개명된) 컨트롤러가 분류 목록에 남아 있습니다: ${stale.join(', ')}`);
});

test('화면이 생긴 컨트롤러는 부채 목록에서 내려간다', () => {
  const called = calledControllers();
  const done = sorted([...SCREEN_PENDING.keys()].filter((key) => called.has(key)));

  assert.deepEqual(done, [],
    `화면이 생겼는데 SCREEN_PENDING 에 남아 있습니다: ${done.join(', ')}\n`
    + `목록에서 지우고 PENDING_BUDGET 을 ${PENDING_BUDGET - done.length} 로 내리세요.`);
});

test('기계 전용으로 선언한 컨트롤러를 화면이 부르지 않는다', () => {
  const called = calledControllers();
  const wrong = sorted([...MACHINE_ONLY.keys()].filter((key) => called.has(key)));

  assert.deepEqual(wrong, [],
    `기계 전용으로 분류된 컨트롤러를 프론트가 부르고 있습니다: ${wrong.join(', ')}\n`
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
  // 추출 정규식이 깨져 0개가 되면 위 테스트들은 조용히 전부 통과한다.
  assert.ok(controllers().length >= 100, '컨트롤러 스캔 결과가 비정상적으로 적습니다.');
  assert.ok(frontendUrls().size >= 150, '프론트 URL 스캔 결과가 비정상적으로 적습니다.');
});
