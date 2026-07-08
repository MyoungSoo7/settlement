import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';
import { parseCsvLine, parseCsv, num } from '../../common/csv.mjs';
import { loadBooks, runInvariants, resolveDataDir, BooksLoadError } from '../../common/books.mjs';
import { deriveSignals, loadThresholds, DEFAULT_THRESHOLDS, amountMarker, pctMarker, intPctMarker } from '../../common/signals.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SAMPLE_DIR = join(HERE, '..', '..', 'data', 'sample');
const CLEAN_DIR = join(HERE, '..', '..', 'data', 'fixtures', 'clean');
const DETECT_CLI = join(HERE, '..', '..', 'bin', 'detect-signals.mjs');
const VERIFY_CLI = join(HERE, '..', '..', 'bin', 'verify-books.mjs');
const EVAL_CLI = join(HERE, '..', 'briefing-eval.mjs');

// ── csv.mjs ──────────────────────────────────────────────────
test('csv — 따옴표 필드(콤마 포함)·이스케이프 따옴표 파싱', () => {
  assert.deepEqual(parseCsvLine('"서울, 마포구",100,"그는 ""철수"" 라 했다"'),
    ['서울, 마포구', '100', '그는 "철수" 라 했다']);
});

test('csv — BOM·CRLF·트레일링 빈 줄 허용', () => {
  const { columns, rows } = parseCsv('﻿a,b\r\n1,2\r\n\r\n');
  assert.deepEqual(columns, ['a', 'b']);
  assert.deepEqual(rows, [{ a: '1', b: '2' }]);
});

test('csv — num: 천단위 콤마·회계 괄호·전각 마이너스, 비숫자는 라벨 포함 오류', () => {
  assert.equal(num('1,750', 'x'), 1750);
  assert.equal(num('(880)', 'x'), -880);
  assert.equal(num('−310', 'x'), -310);
  assert.throws(() => num('abc', '매출.Q1'), /매출\.Q1.*숫자가 아님/);
});

// ── books.mjs ────────────────────────────────────────────────
test('books — 한국어 컬럼 별칭 헤더도 canonical 로 정규화되어 게이트 통과', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-alias-'));
  try {
    writeFileSync(join(dir, 'trial_balance.csv'), [
      '분기,매출,매출채권,재고,계약부채,영업이익,영업현금흐름,변동금리차입금,이자비용',
      '2026Q1,100,50,10,5,20,15,30,2',
      '2026Q2,110,55,11,6,22,16,30,2',
    ].join('\n'));
    writeFileSync(join(dir, 'ar_aging.csv'), [
      '분기,거래처,정상,31~60일,61~90일,90일초과',
      '2026Q1,가나상사,20,15,10,5',
      '2026Q2,가나상사,25,15,10,5',
    ].join('\n'));
    writeFileSync(join(dir, 'cost_allocation.csv'), [
      '제품,매출,직접원가,공통원가배부액,배부기준비중,기계시간비중,영업이익',
      'P1,110,60,30,100,100,20',
    ].join('\n'));
    const books = loadBooks(dir);
    assert.equal(books.latestQuarter, '2026Q2');
    const gate = runInvariants(books);
    assert.equal(gate.gate, 'PASS', JSON.stringify(gate.checks, null, 2));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('books — 필수 컬럼 누락은 별칭 목록과 현재 헤더를 담은 BooksLoadError', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-col-'));
  try {
    writeFileSync(join(dir, 'trial_balance.csv'), 'quarter,sales\n2026Q1,100\n');
    writeFileSync(join(dir, 'ar_aging.csv'), 'quarter,customer,current,d31_60,d61_90,d90_plus\n2026Q1,a,1,1,1,1\n');
    writeFileSync(join(dir, 'cost_allocation.csv'), 'product,sales,direct_cost,allocated_common_cost,allocation_basis_sales_pct,actual_machine_hours_pct,operating_income\nP,1,1,1,100,100,-1\n');
    assert.throws(() => loadBooks(dir), (e) =>
      e instanceof BooksLoadError && /accounts_receivable/.test(e.message) && /매출채권/.test(e.message));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('books — 필수 파일 누락·디렉터리 없음은 BooksLoadError', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-file-'));
  try {
    writeFileSync(join(dir, 'trial_balance.csv'), 'quarter,sales\n2026Q1,100\n');
    assert.throws(() => loadBooks(dir), /ar_aging\.csv.*cost_allocation\.csv/);
    assert.throws(() => loadBooks(join(dir, 'no-such')), /데이터 디렉터리 없음/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('books — resolveDataDir: --data-dir 플래그 > env > 기본값', () => {
  const prev = process.env.VERIFY_BOOKS_DATA_DIR;
  try {
    process.env.VERIFY_BOOKS_DATA_DIR = 'envdir';
    assert.equal(resolveDataDir(['--data-dir', 'flagdir'], 'def'), 'flagdir');
    assert.equal(resolveDataDir([], 'def'), 'envdir');
    delete process.env.VERIFY_BOOKS_DATA_DIR;
    assert.equal(resolveDataDir([], 'def'), 'def');
  } finally {
    if (prev === undefined) delete process.env.VERIFY_BOOKS_DATA_DIR;
    else process.env.VERIFY_BOOKS_DATA_DIR = prev;
  }
});

// ── signals.mjs ──────────────────────────────────────────────
test('signals — 마커 빌더: 금액(콤마 허용)·비율(1자리)·정수 %', () => {
  assert.match('1,750', amountMarker(1750));
  assert.match('1750', amountMarker(1750));
  assert.match('-880', amountMarker(-880));
  assert.match('△880', amountMarker(-880));
  assert.match('46.9%', pctMarker(46.94));
  assert.match('46,9%', pctMarker(46.94));
  assert.match('45 %', intPctMarker(45));
});

test('signals — 샘플 데이터: 4신호 PRESENT, 근거 수치가 손계산과 일치', () => {
  const signals = deriveSignals(loadBooks(SAMPLE_DIR));
  assert.equal(signals.filter((s) => s.present).length, 4);
  const [s1, s2, s3, s4] = ['S1', 'S2', 'S3', 'S4'].map((id) => signals.find((s) => s.id === id));
  assert.equal(s1.evidence.salesGrowthPct, 11.3);
  assert.equal(s1.evidence.arGrowthPct, 46.9);
  assert.equal(s2.evidence.topSharePct, 91.1);
  assert.equal(s2.evidence.topCustomer, '에이프릴리테일');
  const worst = s3.evidence.distortedProducts[0];
  assert.equal(worst.product, '제품C');
  assert.equal(worst.recomputedIncome, -880);
  assert.equal(worst.flipped, true);
  assert.match(s4.evidence.interestCoverage, /23\.9→8\.9/);
  // 파생 마커가 실제 브리핑 문장과 매칭되는지 (채점기 계약)
  assert.ok(s1.markers.some((m) => m.test('매출채권 +46.9% 증가')));
  assert.ok(s4.markers.some((m) => m.test('변동금리 차입 14,500')));
});

test('signals — clean 픽스처: 4신호 전부 absent', () => {
  const signals = deriveSignals(loadBooks(CLEAN_DIR));
  assert.equal(signals.filter((s) => s.present).length, 0);
  assert.ok(signals.every((s) => s.markers.length === 0));
});

test('signals — 분기 1개뿐이면 S1/S4 는 evaluable=false (판정 불가, 지어내지 않음)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-oneq-'));
  try {
    writeFileSync(join(dir, 'trial_balance.csv'), [
      'quarter,sales,accounts_receivable,inventory,contract_liability,operating_income,operating_cash_flow,variable_rate_debt,interest_expense',
      '2026Q2,100,50,10,5,20,15,30,2',
    ].join('\n'));
    writeFileSync(join(dir, 'ar_aging.csv'), 'quarter,customer,current,d31_60,d61_90,d90_plus\n2026Q2,가나상사,20,15,10,5\n');
    writeFileSync(join(dir, 'cost_allocation.csv'), [
      'product,sales,direct_cost,allocated_common_cost,allocation_basis_sales_pct,actual_machine_hours_pct,operating_income',
      'P1,100,50,30,100,100,20',
    ].join('\n'));
    const signals = deriveSignals(loadBooks(dir));
    const s1 = signals.find((s) => s.id === 'S1');
    const s4 = signals.find((s) => s.id === 'S4');
    assert.equal(s1.evaluable, false);
    assert.equal(s1.present, false);
    assert.equal(s4.evaluable, false);
    assert.equal(s4.present, false);
    // (단일 거래처 픽스처라 S2 집중도 100% 는 정당한 PRESENT — 판정 불가와 무관)
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('signals — analysis-config.json 으로 임계값 오버라이드 (clean 데이터도 민감 설정이면 PRESENT)', () => {
  const defaults = loadThresholds(CLEAN_DIR);
  assert.deepEqual(defaults, DEFAULT_THRESHOLDS); // 설정 파일 없으면 기본값
  const dir = mkdtempSync(join(tmpdir(), 'tca-cfg-'));
  try {
    for (const f of ['trial_balance.csv', 'ar_aging.csv', 'cost_allocation.csv']) {
      writeFileSync(join(dir, f), '');
    }
    writeFileSync(join(dir, 'analysis-config.json'),
      JSON.stringify({ thresholds: { concentrationSharePct: 30, overdueMaterialityPct: 1 } }));
    const t = loadThresholds(dir);
    assert.equal(t.concentrationSharePct, 30);
    assert.equal(t.arGrowthGapPp, DEFAULT_THRESHOLDS.arGrowthGapPp); // 나머지는 기본 유지
    // clean 픽스처(최대 집중 34.1%)도 민감 임계값에서는 S2 PRESENT 로 뒤집힌다.
    const signals = deriveSignals(loadBooks(CLEAN_DIR), t);
    assert.equal(signals.find((s) => s.id === 'S2').present, true);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── CLI 통합 (detect-signals / verify-books --data-dir / briefing-eval --data-dir) ──
test('detect-signals CLI — 샘플 --json: gate PASS + PRESENT 4건 + 마커 직렬화', () => {
  const r = runNode([DETECT_CLI, '--json']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  const out = JSON.parse(r.stdout);
  assert.equal(out.gate, 'PASS');
  assert.equal(out.presentCount, 4);
  assert.ok(out.signals.every((s) => Array.isArray(s.markers)));
});

test('detect-signals CLI — clean 픽스처: PRESENT 0건 + "이상 없음" 안내', () => {
  const r = runNode([DETECT_CLI, '--data-dir', CLEAN_DIR]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /판정 신호: 0건/);
  assert.match(r.stdout, /지어내면 채점기가 오탐으로 잡음/);
});

test('detect-signals CLI — 게이트 FAIL 데이터에서는 신호 파생 거부 (exit 1)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-gatefail-'));
  try {
    writeFileSync(join(dir, 'trial_balance.csv'), [
      'quarter,sales,accounts_receivable,inventory,contract_liability,operating_income,operating_cash_flow,variable_rate_debt,interest_expense',
      '2026Q2,100,999,1,1,50,10,30,2', // aging 대사 불일치
    ].join('\n'));
    writeFileSync(join(dir, 'ar_aging.csv'), 'quarter,customer,current,d31_60,d61_90,d90_plus\n2026Q2,가,1,1,1,1\n');
    writeFileSync(join(dir, 'cost_allocation.csv'), [
      'product,sales,direct_cost,allocated_common_cost,allocation_basis_sales_pct,actual_machine_hours_pct,operating_income',
      'P1,100,50,30,100,100,20',
    ].join('\n'));
    const r = runNode([DETECT_CLI, '--data-dir', dir]);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /GATE FAIL.*신호를 파생하지 않습니다/);
    const j = runNode([DETECT_CLI, '--data-dir', dir, '--json']);
    assert.equal(j.status, 1);
    assert.deepEqual(JSON.parse(j.stdout).signals, []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('detect-signals CLI — 데이터 폴더 자체가 없으면 로드 오류 (exit 1)', () => {
  const r = runNode([DETECT_CLI, '--data-dir', join(tmpdir(), 'tca-none-xyz')]);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /게이트 진입 불가/);
});

test('verify-books CLI — --data-dir 플래그로 임의 폴더 지정 + 로드 오류는 GATE FAIL', () => {
  const ok = runNode([VERIFY_CLI, '--data-dir', CLEAN_DIR]);
  assert.equal(ok.status, 0, ok.stdout + ok.stderr);
  assert.match(ok.stdout, /GATE PASS/);

  const missing = runNode([VERIFY_CLI, '--data-dir', join(tmpdir(), 'tca-none-xyz')]);
  assert.equal(missing.status, 1);
  assert.match(missing.stdout, /FAIL {2}LOAD 데이터 디렉터리 없음/);
  const json = runNode([VERIFY_CLI, '--data-dir', join(tmpdir(), 'tca-none-xyz'), '--json']);
  assert.equal(json.status, 1);
  assert.equal(JSON.parse(json.stdout).gate, 'FAIL');
});

test('briefing-eval CLI — --data-dir clean 픽스처: 음성 자동 전환 + --json', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-eval-'));
  try {
    const file = join(dir, 'clean-brief.md');
    writeFileSync(file, '# 점검 결과\n## 확인 범위\n결론: 유의미한 이상 신호가 확인되지 않았습니다.\n근거: 대사 일치.\n확신도: 확인됨.\n권고 조치: 유지.\n');
    const r = runNode([EVAL_CLI, '--data-dir', CLEAN_DIR, file]);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /EVAL PASS/);
    assert.match(r.stdout, /\[오탐\] 지어낸 신호 0건/);

    const j = runNode([EVAL_CLI, '--data-dir', CLEAN_DIR, '--json', file]);
    assert.equal(j.status, 0);
    const out = JSON.parse(j.stdout);
    assert.equal(out.negative, true);
    assert.equal(out.pass, true);

    // 같은 브리핑을 "이상 4건" 샘플 데이터 기준으로 채점하면 재현율 미달 FAIL.
    const wrong = runNode([EVAL_CLI, file]);
    assert.equal(wrong.status, 1);
    assert.match(wrong.stdout, /\[재현율\] 0\/4/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
