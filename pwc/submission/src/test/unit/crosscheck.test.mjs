import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';
import { loadBooks } from '../../common/books.mjs';
import {
  findCompleteFiscalYears, sumInternalYear, extractDartTotals,
  runDartCrosscheck, loadCrosscheckConfig, DEFAULT_CROSSCHECK,
} from '../../common/crosscheck.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SAMPLE_DIR = join(HERE, '..', '..', 'data', 'sample');
const VERIFY_CLI = join(HERE, '..', '..', 'bin', 'verify-books.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

/** INV-1~7 을 통과하는 완결 회계연도(2027Q1~Q4) 장부를 임시 폴더에 만든다. */
function writeFullYearBooks(dir) {
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
}

// 매출 합 460 / 영업이익 합 46 (unitScale 1e6 → 460,000,000 / 46,000,000).
const DART_MATCH_BODY = {
  status: '000', message: '정상',
  list: [
    { fs_div: 'CFS', sj_div: 'IS', account_nm: '매출액', thstrm_amount: '999,999,999' },
    { fs_div: 'CFS', sj_div: 'IS', account_nm: '영업이익', thstrm_amount: '999,999,999' },
    { fs_div: 'OFS', sj_div: 'BS', account_nm: '자산총계', thstrm_amount: '123' },
    { fs_div: 'OFS', sj_div: 'IS', account_nm: '매출액', thstrm_amount: '460,000,000' },
    { fs_div: 'OFS', sj_div: 'IS', account_nm: '영업이익(손실)', thstrm_amount: '46,000,000' },
  ],
};

// ── 순수 함수 ────────────────────────────────────────────────
test('crosscheck — findCompleteFiscalYears: 샘플(2025Q3~2026Q2)은 완결 연도 없음', () => {
  assert.deepEqual(findCompleteFiscalYears(loadBooks(SAMPLE_DIR)), []);
});

test('crosscheck — findCompleteFiscalYears/sumInternalYear: 완결 연도 합산', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-cc-'));
  try {
    writeFullYearBooks(dir);
    const books = loadBooks(dir);
    assert.deepEqual(findCompleteFiscalYears(books), [2027]);
    assert.deepEqual(sumInternalYear(books, 2027), { sales: 460, operatingIncome: 46 });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('crosscheck — extractDartTotals: fs_div 필터 + 계정 별칭 + 콤마 금액', () => {
  const ofs = extractDartTotals(DART_MATCH_BODY, 'OFS');
  assert.deepEqual(ofs, { sales: 460_000_000, operatingIncome: 46_000_000 });
  const cfs = extractDartTotals(DART_MATCH_BODY, 'CFS');
  assert.equal(cfs.sales, 999_999_999);
  assert.deepEqual(extractDartTotals({ list: [] }, 'OFS'), { sales: null, operatingIncome: null });
});

test('crosscheck — loadCrosscheckConfig: 설정 없으면 기본값, 있으면 crosscheck 섹션 병합', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-cccfg-'));
  try {
    assert.deepEqual(loadCrosscheckConfig(dir), DEFAULT_CROSSCHECK);
    writeFileSync(join(dir, 'analysis-config.json'),
      JSON.stringify({ crosscheck: { corpCode: '00126380', unitScale: 1000000 } }));
    const cfg = loadCrosscheckConfig(dir);
    assert.equal(cfg.corpCode, '00126380');
    assert.equal(cfg.unitScale, 1000000);
    assert.equal(cfg.fsDiv, 'OFS'); // 기본 유지
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── runDartCrosscheck (fetch 주입) ───────────────────────────
test('crosscheck — 일치(허용오차 내)면 INV-8 pass + 근거 수치 detail', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-ccok-'));
  try {
    writeFullYearBooks(dir);
    const calls = [];
    const r = await runDartCrosscheck(loadBooks(dir),
      { corpCode: '00126380', unitScale: 1_000_000 },
      async (args) => { calls.push(args); return DART_MATCH_BODY; });
    assert.equal(r.pass, true, r.detail);
    assert.match(r.detail, /2027 사업보고서 OFS 기준/);
    assert.match(r.detail, /차이 0%/);
    assert.deepEqual(calls, [{ corpCode: '00126380', year: 2027, reprtCode: '11011' }]); // 연도 자동 파생
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('crosscheck — 공시와 불일치(오차 초과)면 INV-8 FAIL', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-ccbad-'));
  try {
    writeFullYearBooks(dir);
    const body = JSON.parse(JSON.stringify(DART_MATCH_BODY));
    body.list.find((r) => r.fs_div === 'OFS' && r.account_nm === '매출액').thstrm_amount = '500,000,000';
    const r = await runDartCrosscheck(loadBooks(dir), { corpCode: '00126380', unitScale: 1_000_000 }, async () => body);
    assert.equal(r.pass, false);
    assert.match(r.detail, /매출: 내부 460,000,000 vs 공시 500,000,000/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('crosscheck — 판정 불가 경로: 자동 연도 없음=skip / 명시 연도 미커버=FAIL / 013=FAIL / 조회 오류=FAIL', async () => {
  const sample = loadBooks(SAMPLE_DIR); // 완결 연도 없음
  const skip = await runDartCrosscheck(sample, { corpCode: '00126380' }, async () => { throw new Error('호출되면 안 됨'); });
  assert.equal(skip.skipped, true);
  assert.equal(skip.pass, true);
  assert.match(skip.detail, /판정 생략/);

  const explicit = await runDartCrosscheck(sample, { corpCode: '00126380', year: 2026 }, async () => DART_MATCH_BODY);
  assert.equal(explicit.pass, false);
  assert.match(explicit.detail, /2026년 Q1~Q4 를 모두 포함하지 않음/);

  const dir = mkdtempSync(join(tmpdir(), 'tca-ccerr-'));
  try {
    writeFullYearBooks(dir);
    const books = loadBooks(dir);
    const noData = await runDartCrosscheck(books, { corpCode: '00126380' }, async () => ({ status: '013', list: [] }));
    assert.equal(noData.pass, false);
    assert.match(noData.detail, /사업보고서 주요계정 없음/);

    const netErr = await runDartCrosscheck(books, { corpCode: '00126380' }, async () => { throw new Error('HTTP 500'); });
    assert.equal(netErr.pass, false);
    assert.match(netErr.detail, /DART 조회 실패 — HTTP 500/);

    const noAccount = await runDartCrosscheck(books, { corpCode: '00126380', fsDiv: 'CFS' },
      async () => ({ status: '000', list: [{ fs_div: 'CFS', sj_div: 'BS', account_nm: '자산총계', thstrm_amount: '1' }] }));
    assert.equal(noAccount.pass, false);
    assert.match(noAccount.detail, /손익 계정.*찾지 못함/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── CLI 통합 (fetch 프리로드 스텁 — 네트워크 0) ──────────────
test('verify-books CLI — --dart-corp-code: INV-8 포함 8종 PASS / 불일치 GATE FAIL / 미설정 7종 유지', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-cccli-'));
  try {
    writeFullYearBooks(dir);
    const stubOk = join(dir, 'stub-ok.json');
    writeFileSync(stubOk, JSON.stringify({ rules: [{ match: 'fnlttSinglAcnt.json', json: DART_MATCH_BODY }] }));
    const args = [VERIFY_CLI, '--data-dir', dir, '--dart-corp-code', '00126380', '--dart-unit-scale', '1000000'];

    const ok = runNode([...args, '--json'], withFetchStub(PRELOAD, stubOk, { DART_API_KEY: 'test-key' }));
    assert.equal(ok.status, 0, ok.stdout + ok.stderr);
    const summary = JSON.parse(ok.stdout);
    assert.equal(summary.gate, 'PASS');
    assert.equal(summary.checks.length, 8);
    assert.equal(summary.checks.at(-1).id, 'INV-8');

    const badBody = JSON.parse(JSON.stringify(DART_MATCH_BODY));
    badBody.list.find((r) => r.fs_div === 'OFS' && r.account_nm === '매출액').thstrm_amount = '500,000,000';
    const stubBad = join(dir, 'stub-bad.json');
    writeFileSync(stubBad, JSON.stringify({ rules: [{ match: 'fnlttSinglAcnt.json', json: badBody }] }));
    const bad = runNode(args, withFetchStub(PRELOAD, stubBad, { DART_API_KEY: 'test-key' }));
    assert.equal(bad.status, 1);
    assert.match(bad.stdout, /FAIL {2}INV-8/);
    assert.match(bad.stdout, /GATE FAIL/);

    // corp_code 미설정이면 INV-8 자체가 없다 (비상장 고객사 — 범용성 유지).
    const plain = runNode([VERIFY_CLI, '--data-dir', dir, '--json']);
    assert.equal(JSON.parse(plain.stdout).checks.length, 7);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('verify-books CLI — 완결 연도 없는 데이터 + corp_code 는 skip 표시 (게이트는 PASS)', () => {
  const r = runNode([VERIFY_CLI, '--dart-corp-code', '00126380']); // 샘플 데이터: 2025Q3~2026Q2
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /skip {2}INV-8/);
  assert.match(r.stdout, /GATE PASS/);
});
