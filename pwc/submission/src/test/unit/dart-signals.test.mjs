import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';
import {
  extractFullSeries, totalBorrowings, analyzeDisclosures,
  deriveExternalSignals, trillionMarker, toTrillion,
} from '../../common/dart-signals.mjs';
import { evaluateBriefing, signalsFromPacket } from '../briefing-eval.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIAG_CLI = join(HERE, '..', '..', 'bin', 'diagnose-company.mjs');
const EVAL_CLI = join(HERE, '..', 'briefing-eval.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

const row = (sj, id, nm, [a0, a1, a2]) => ({
  sj_div: sj, account_id: id, account_nm: nm,
  bfefrmtrm_amount: String(a0), frmtrm_amount: String(a1), thstrm_amount: String(a2),
});

// 스트레스 기업 — E1~E4 전부 발화하도록 설계된 3개년 공시.
const STRESSED_BODY = {
  status: '000',
  list: [
    row('IS', 'ifrs-full_Revenue', '매출액', [800, 900, 1000]),                       // +11.1%
    row('IS', 'dart_OperatingIncomeLoss', '영업이익', [80, 90, 100]),
    row('BS', 'ifrs-full_CurrentTradeReceivables', '매출채권', [200, 220, 330]),      // +50% → gap 38.9
    row('BS', 'ifrs-full_Inventories', '재고자산', [100, 110, 160]),                  // +45.5% → gap 34.4
    row('BS', 'ifrs-full_CurrentAssets', '유동자산', [300, 280, 260]),
    row('BS', 'ifrs-full_CurrentLiabilities', '유동부채', [200, 210, 230]),           // 유동비율 150→133.3→113
    row('BS', '-표준계정코드 미사용-', '단기차입금', [50, 60, 90]),                    // 이름 폴백 매칭
    row('BS', 'ifrs-full_NoncurrentPortionOfNoncurrentLoansReceived', '장기차입금', [10, 20, 30]), // 합계 +50%
    row('CF', 'ifrs-full_CashFlowsFromUsedInOperatingActivities', '영업활동현금흐름', [70, 60, 40]), // OCF/OI 0.4
    row('CF', 'ifrs-full_InterestPaidClassifiedAsOperatingActivities', '이자의 지급', [5, 8, 15]),  // 보상 11.3→6.7배
  ],
};

// 건전 기업 — 전부 absent.
const HEALTHY_BODY = {
  status: '000',
  list: [
    row('IS', 'ifrs-full_Revenue', '매출액', [800, 900, 1000]),
    row('IS', 'dart_OperatingIncomeLoss', '영업이익', [80, 90, 100]),
    row('BS', 'ifrs-full_CurrentTradeReceivables', '매출채권', [160, 180, 200]),
    row('BS', 'ifrs-full_Inventories', '재고자산', [80, 90, 100]),
    row('BS', 'ifrs-full_CurrentAssets', '유동자산', [300, 320, 340]),
    row('BS', 'ifrs-full_CurrentLiabilities', '유동부채', [200, 200, 200]),
    row('BS', '-표준계정코드 미사용-', '단기차입금', [50, 50, 50]),
    row('CF', 'ifrs-full_CashFlowsFromUsedInOperatingActivities', '영업활동현금흐름', [120, 135, 150]),
    row('CF', 'ifrs-full_InterestPaidClassifiedAsOperatingActivities', '이자의 지급', [2, 2, 2]),
  ],
};

const STRESSED_DISCLOSURES = [
  { report_nm: '[기재정정]장래사업ㆍ경영계획(공정공시)', rcept_dt: '20260629' },
  { report_nm: '[기재정정]임원ㆍ주요주주특정증권등소유상황보고서', rcept_dt: '20260602' },
  { report_nm: '[첨부정정]사업보고서', rcept_dt: '20260520' },
  { report_nm: '풍문또는보도에대한해명(미확정)', rcept_dt: '20260624' },
  { report_nm: '분기보고서', rcept_dt: '20260515' },
];
const QUIET_DISCLOSURES = [{ report_nm: '분기보고서', rcept_dt: '20260515' }];

// ── 추출기 ───────────────────────────────────────────────────
test('dart-signals — extractFullSeries: account_id 우선 + 계정명 폴백 + 결측 null', () => {
  const s = extractFullSeries(STRESSED_BODY);
  assert.deepEqual(s.revenue, [800, 900, 1000]);
  assert.deepEqual(s.shortBorrowings, [50, 60, 90]); // 표준코드 미사용 → 이름 폴백
  assert.equal(s.bonds, null);                        // 사채 계정 없음
  const dash = extractFullSeries({ list: [row('IS', 'ifrs-full_Revenue', '매출액', ['-', '', 1000])] });
  assert.deepEqual(dash.revenue, [null, null, 1000]);
});

test('dart-signals — totalBorrowings: 존재 항목 합산, 전부 없으면 null', () => {
  assert.deepEqual(totalBorrowings(extractFullSeries(STRESSED_BODY)), [60, 80, 120]);
  assert.equal(totalBorrowings({ shortBorrowings: null, longBorrowings: null, bonds: null }), null);
});

test('dart-signals — analyzeDisclosures / trillionMarker / toTrillion', () => {
  const d = analyzeDisclosures(STRESSED_DISCLOSURES);
  assert.equal(d.corrections.length, 3);
  assert.equal(d.clarifications.length, 1);
  assert.equal(d.total, 5);
  assert.match('24.1조', trillionMarker(24_060_000_000_000));
  assert.match('24,1 조', trillionMarker(24_060_000_000_000));
  assert.equal(toTrillion(43_623_073_000_000), 43.6);
});

// ── 외부 신호 파생 ───────────────────────────────────────────
test('dart-signals — 스트레스 공시: E1~E5 전부 PRESENT + 근거 수치', () => {
  const series = extractFullSeries(STRESSED_BODY);
  const signals = deriveExternalSignals({ series, disclosures: STRESSED_DISCLOSURES, days: 90 });
  assert.deepEqual(signals.filter((s) => s.present).map((s) => s.id), ['E1', 'E2', 'E3', 'E4', 'E5']);
  const e1 = signals.find((s) => s.id === 'E1');
  assert.equal(e1.evidence.receivablesGrowthPct, 50);
  assert.equal(e1.evidence.ocfToOperatingIncome, 0.4);
  const e3 = signals.find((s) => s.id === 'E3');
  assert.equal(e3.evidence.borrowingsGrowthPct, 50);
  const e4 = signals.find((s) => s.id === 'E4');
  assert.equal(e4.evidence.fallingTwoYears, true);
});

test('dart-signals — 건전 공시: 전부 absent (마커 0)', () => {
  const series = extractFullSeries(HEALTHY_BODY);
  const signals = deriveExternalSignals({ series, disclosures: QUIET_DISCLOSURES, days: 90 });
  assert.equal(signals.filter((s) => s.present).length, 0);
  assert.ok(signals.every((s) => s.markers.length === 0));
});

test('dart-signals — 계정 결측이면 해당 신호 evaluable=false (지어내지 않음)', () => {
  const body = { status: '000', list: [row('IS', 'ifrs-full_Revenue', '매출액', [800, 900, 1000])] };
  const signals = deriveExternalSignals({ series: extractFullSeries(body), disclosures: null });
  const byId = Object.fromEntries(signals.map((s) => [s.id, s]));
  for (const id of ['E1', 'E2', 'E3', 'E4', 'E5']) {
    assert.equal(byId[id].evaluable, false, id);
    assert.equal(byId[id].present, false, id);
  }
});

// ── 진단 패킷 → 채점기 연동 (--signals-file 경로) ────────────
test('briefing-eval — signalsFromPacket: 직렬화 마커 복원 + E1 채점 성립', () => {
  const series = extractFullSeries(STRESSED_BODY);
  const derived = deriveExternalSignals({ series, disclosures: STRESSED_DISCLOSURES, days: 90 });
  const packet = { signals: derived.map((s) => ({ ...s, markers: s.markers.map((m) => m.source), categoryPattern: s.categoryPattern.source })) };
  const hydrated = signalsFromPacket(packet);
  assert.ok(hydrated.every((s) => s.categoryPattern instanceof RegExp));

  const briefing = `# 진단
## 1. 채권 리스크
결론: 매출 11.1% 성장 대비 매출채권 50% 급증 — 영업현금흐름 기준 이익의 현금화 저조.
확신도: 가설 — 분기보고서 대조 확인 필요.
근거·권고 조치: 채권 연령 분석 요청.
`;
  const r = evaluateBriefing(briefing, { signals: hydrated });
  assert.equal(r.signals.find((s) => s.id === 'E1').detected, true);
  assert.equal(r.total, 5);
  assert.throws(() => signalsFromPacket({}), /signals 배열이 없습니다/);
});

// ── diagnose-company CLI (fetch 프리로드 — 네트워크 0) ───────
function stubRules({ fullBody, listRows }) {
  return {
    rules: [
      { match: 'fnlttSinglAcntAll.json', json: fullBody }, // SinglAcnt 보다 먼저 (substring 겹침)
      { match: 'fnlttSinglAcnt.json', json: { status: '000', list: [
        { fs_div: 'OFS', sj_div: 'IS', account_nm: '매출액', thstrm_amount: '460,000,000' },
        { fs_div: 'OFS', sj_div: 'IS', account_nm: '영업이익', thstrm_amount: '46,000,000' },
      ] } },
      { match: 'company.json', json: { status: '000', corp_name: '테스트전자(주)', stock_code: '123456', ceo_nm: '홍길동', bizr_no: '1234567890' } },
      { match: 'list.json', json: { status: '000', total_count: listRows.length, list: listRows } },
      // ECOS 는 규칙 없음 → 599 → 거시 컨텍스트 생략 경로
    ],
  };
}

test('diagnose-company CLI — 기본 모드(--json): 외부 신호 5건 + 거시 생략 + 내부 null', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-diag-'));
  try {
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify(stubRules({ fullBody: STRESSED_BODY, listRows: STRESSED_DISCLOSURES })));
    const r = runNode([DIAG_CLI, '--corp-code', '00000001', '--year', '2025', '--json'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key' }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const out = JSON.parse(r.stdout);
    assert.equal(out.corp.name, '테스트전자(주)');
    assert.equal(out.externalPresent, 5);
    assert.equal(out.macro, null);
    assert.ok(out.signals.every((s) => typeof s.categoryPattern === 'string'));
    assert.equal(out.internal, null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('diagnose-company CLI — 상세 모드(--data-dir): 게이트+내부 신호+INV-8 자동 배선', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-diag2-'));
  try {
    // 완결 회계연도 내부 장부 (INV-1~7 PASS, 연 합계 매출 460/영업이익 46)
    writeFileSync(join(dir, 'trial_balance.csv'), [
      'quarter,sales,accounts_receivable,inventory,contract_liability,operating_income,operating_cash_flow,variable_rate_debt,interest_expense',
      '2027Q1,100,40,5,5,10,8,10,1',
      '2027Q2,110,42,5,5,11,9,10,1',
      '2027Q3,120,44,5,5,12,10,10,1',
      '2027Q4,130,46,5,5,13,11,10,1',
    ].join('\n'));
    writeFileSync(join(dir, 'ar_aging.csv'),
      'quarter,customer,current,d31_60,d61_90,d90_plus\n2027Q4,가나상사,46,0,0,0\n');
    writeFileSync(join(dir, 'cost_allocation.csv'), [
      'product,sales,direct_cost,allocated_common_cost,allocation_basis_sales_pct,actual_machine_hours_pct,operating_income',
      'P1,130,50,30,100,100,50',
    ].join('\n'));
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify(stubRules({ fullBody: HEALTHY_BODY, listRows: QUIET_DISCLOSURES })));

    const r = runNode(
      [DIAG_CLI, '--corp-code', '00000001', '--year', '2025', '--data-dir', dir, '--dart-unit-scale', '1000000', '--json'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key' }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const out = JSON.parse(r.stdout);
    assert.equal(out.externalPresent, 0);
    assert.equal(out.internal.gate, 'PASS');
    assert.equal(out.internal.signals.length, 4);
    assert.equal(out.internal.crosscheck.id, 'INV-8');
    assert.equal(out.internal.crosscheck.pass, true, out.internal.crosscheck.detail);

    // 사람용 출력에도 두 층이 모두 보인다.
    const human = runNode(
      [DIAG_CLI, '--corp-code', '00000001', '--year', '2025', '--data-dir', dir, '--dart-unit-scale', '1000000'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key' }));
    assert.match(human.stdout, /\[외부 신호 — 공시 기반\] PRESENT 0건/);
    assert.match(human.stdout, /\[내부 상세 모드\] 게이트 PASS/);
    assert.match(human.stdout, /ok {2}INV-8/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('diagnose-company CLI — 식별 인자 없으면 사용법 (exit 1)', () => {
  const r = runNode([DIAG_CLI]);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /사용법/);
});

// ── briefing-eval CLI --signals-file ─────────────────────────
test('briefing-eval CLI — --signals-file 로 진단 패킷 기준 채점', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-sigfile-'));
  try {
    const series = extractFullSeries(HEALTHY_BODY);
    const derived = deriveExternalSignals({ series, disclosures: QUIET_DISCLOSURES, days: 90 });
    const packet = join(dir, 'packet.json');
    writeFileSync(packet, JSON.stringify({
      signals: derived.map((s) => ({ ...s, markers: s.markers.map((m) => m.source), categoryPattern: s.categoryPattern.source })),
    }));
    const brief = join(dir, 'brief.md');
    writeFileSync(brief, '# 점검 결과\n## 확인 범위\n결론: 유의미한 이상 신호가 확인되지 않았습니다.\n근거: 공시 기반 5개 축 점검.\n확신도: 확인됨.\n권고 조치: 유지.\n');
    const r = runNode([EVAL_CLI, '--signals-file', packet, brief]);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /EVAL PASS/); // PRESENT 0건 → 자동 음성 채점
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
