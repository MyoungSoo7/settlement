#!/usr/bin/env node
/**
 * 불변식 게이트 (verify-books) — LLM 추론 전에 기계적으로 확정 가능한 정합성을 먼저 검증한다.
 *
 * 철학 (doc/회계.md): "불변식(시산표 균형, 대사 일치)으로 확정할 수 있는 것을 먼저
 * 기계적으로 확정하고, 그 위에서만 AI 추론이 의미를 갖는다."
 * 이 게이트가 FAIL 이면 에이전트는 리스크 추론에 진입하지 않고,
 * 실패 항목 자체를 "데이터 품질 리스크"로 보고해야 한다.
 *
 * 사용:
 *   node src/bin/verify-books.mjs          # 사람이 읽는 표 + exit code
 *   node src/bin/verify-books.mjs --json   # 기계가 읽는 JSON
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// VERIFY_BOOKS_DATA_DIR: 테스트가 위반 시나리오 픽스처를 주입하는 지점
const DATA_DIR = process.env.VERIFY_BOOKS_DATA_DIR
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'sample');
const AMOUNT_TOLERANCE = 0.01;

function readCsv(name) {
  const text = readFileSync(join(DATA_DIR, name), 'utf8').trim();
  const [header, ...rows] = text.split(/\r?\n/);
  const cols = header.split(',').map((c) => c.trim());
  return rows.filter(Boolean).map((line) => {
    const cells = line.split(',').map((c) => c.trim());
    return Object.fromEntries(cols.map((c, i) => [c, cells[i]]));
  });
}

function num(value, label) {
  const n = Number(value);
  if (!Number.isFinite(n)) throw new Error(`${label} 가 숫자가 아님: "${value}"`);
  return n;
}

const near = (a, b) => Math.abs(a - b) <= AMOUNT_TOLERANCE;

const results = [];
function check(id, name, fn) {
  try {
    const detail = fn();
    results.push({ id, name, pass: true, detail: detail ?? '' });
  } catch (error) {
    results.push({ id, name, pass: false, detail: error.message });
  }
}

// ── 데이터 로드 ──────────────────────────────────────────────
const trialBalance = readCsv('trial_balance.csv');
const arAging = readCsv('ar_aging.csv');
const costAllocation = readCsv('cost_allocation.csv');

const tbByQuarter = new Map(trialBalance.map((r) => [r.quarter, r]));
const AGING_BUCKETS = ['current', 'd31_60', 'd61_90', 'd90_plus'];

// ── INV-1: 시산표 수치 필드 전체 파싱 가능 ──────────────────
check('INV-1', '시산표 수치 필드가 전부 유효한 숫자', () => {
  const numericCols = Object.keys(trialBalance[0]).filter((c) => c !== 'quarter');
  for (const row of trialBalance) {
    for (const col of numericCols) num(row[col], `trial_balance ${row.quarter}.${col}`);
  }
  return `${trialBalance.length}개 분기 × ${numericCols.length}개 컬럼`;
});

// ── INV-2: 채권 aging 분기 합계 = 시산표 매출채권 (대사) ────
check('INV-2', '채권 aging 분기 합계 = 시산표 매출채권 (대사 일치)', () => {
  const agingByQuarter = new Map();
  for (const row of arAging) {
    const sum = AGING_BUCKETS.reduce((acc, b) => acc + num(row[b], `ar_aging ${row.quarter}/${row.customer}.${b}`), 0);
    agingByQuarter.set(row.quarter, (agingByQuarter.get(row.quarter) ?? 0) + sum);
  }
  const details = [];
  for (const [quarter, total] of agingByQuarter) {
    const tb = tbByQuarter.get(quarter);
    if (!tb) throw new Error(`aging 의 분기 ${quarter} 가 시산표에 없음`);
    const expected = num(tb.accounts_receivable, `trial_balance ${quarter}.accounts_receivable`);
    if (!near(total, expected)) {
      throw new Error(`${quarter}: aging 합계 ${total} ≠ 시산표 매출채권 ${expected}`);
    }
    details.push(`${quarter}=${total}`);
  }
  return details.join(', ');
});

// ── INV-3: 원가표 매출 합계 = 시산표 해당 분기 매출 ─────────
check('INV-3', '원가배분표 매출 합계 = 시산표 2026Q2 매출', () => {
  const total = costAllocation.reduce((acc, r) => acc + num(r.sales, `cost_allocation ${r.product}.sales`), 0);
  const expected = num(tbByQuarter.get('2026Q2').sales, 'trial_balance 2026Q2.sales');
  if (!near(total, expected)) throw new Error(`원가표 매출 합 ${total} ≠ 시산표 매출 ${expected}`);
  return `${total}`;
});

// ── INV-4: 공통원가 배부액이 배부 기준(매출 비중)과 비례 ────
check('INV-4', '공통원가 배부액 = 공통원가 총액 × 배부 기준 비중', () => {
  const totalCommon = costAllocation.reduce(
    (acc, r) => acc + num(r.allocated_common_cost, `cost_allocation ${r.product}.allocated_common_cost`), 0);
  for (const row of costAllocation) {
    const expected = (totalCommon * num(row.allocation_basis_sales_pct, `${row.product}.allocation_basis_sales_pct`)) / 100;
    const actual = num(row.allocated_common_cost, `${row.product}.allocated_common_cost`);
    if (!near(actual, expected)) {
      throw new Error(`${row.product}: 배부액 ${actual} ≠ 총액 ${totalCommon} × 비중 → ${expected}`);
    }
  }
  return `공통원가 총액 ${totalCommon}`;
});

// ── INV-5: 배부 기준 비중 합 100%, 실제 기계시간 비중 합 100% ─
check('INV-5', '배부 기준 비중 합계 100% / 실제 기계시간 비중 합계 100%', () => {
  const basisSum = costAllocation.reduce((acc, r) => acc + num(r.allocation_basis_sales_pct, 'basis_pct'), 0);
  const hoursSum = costAllocation.reduce((acc, r) => acc + num(r.actual_machine_hours_pct, 'hours_pct'), 0);
  if (!near(basisSum, 100)) throw new Error(`배부 기준 비중 합 ${basisSum} ≠ 100`);
  if (!near(hoursSum, 100)) throw new Error(`기계시간 비중 합 ${hoursSum} ≠ 100`);
  return `기준 ${basisSum} / 기계시간 ${hoursSum}`;
});

// ── INV-6: 제품별 영업이익 = 매출 − 직접원가 − 배부 공통원가 ─
check('INV-6', '제품별 영업이익 검산 (매출 − 직접원가 − 배부 공통원가)', () => {
  for (const row of costAllocation) {
    const expected = num(row.sales, 'sales') - num(row.direct_cost, 'direct_cost')
      - num(row.allocated_common_cost, 'allocated_common_cost');
    const actual = num(row.operating_income, `${row.product}.operating_income`);
    if (!near(actual, expected)) throw new Error(`${row.product}: 영업이익 ${actual} ≠ 검산 ${expected}`);
  }
  return costAllocation.map((r) => `${r.product}=${r.operating_income}`).join(', ');
});

// ── INV-7: 금리 노출 필드(변동금리 차입·이자비용) 유효성 ────
check('INV-7', '변동금리 차입·이자비용 필드 유효 (이자보상배율 계산 가능)', () => {
  const details = [];
  for (const row of trialBalance) {
    const debt = num(row.variable_rate_debt, `${row.quarter}.variable_rate_debt`);
    const interest = num(row.interest_expense, `${row.quarter}.interest_expense`);
    if (debt <= 0 || interest <= 0) throw new Error(`${row.quarter}: 차입 ${debt} / 이자 ${interest} — 양수가 아님`);
    details.push(`${row.quarter} 이자보상 ${(num(row.operating_income, 'oi') / interest).toFixed(1)}배`);
  }
  return details.join(', ');
});

// ── 출력 ─────────────────────────────────────────────────────
const failures = results.filter((r) => !r.pass);
const summary = {
  gate: failures.length === 0 ? 'PASS' : 'FAIL',
  checks: results,
};

if (process.argv.includes('--json')) {
  console.log(JSON.stringify(summary, null, 2));
} else {
  console.log('=== 불변식 게이트 (verify-books) ===');
  for (const r of results) {
    console.log(`${r.pass ? '  ok' : 'FAIL'}  ${r.id} ${r.name}${r.detail ? ` — ${r.detail}` : ''}`);
  }
  console.log(failures.length === 0
    ? '\nGATE PASS — 기계적 정합성 확정. 추론 단계 진입 가능.'
    : `\nGATE FAIL — ${failures.length}건 위반. 리스크 추론에 진입하지 말고 데이터 품질 리스크로 보고할 것.`);
}

if (failures.length > 0) process.exitCode = 1;
