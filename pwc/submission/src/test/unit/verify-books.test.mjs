import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SCRIPT = join(HERE, '..', '..', 'bin', 'verify-books.mjs');

test('verify-books — 실제 샘플 데이터는 GATE PASS (사람용 출력, exit 0)', () => {
  const r = runNode([SCRIPT]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /GATE PASS/);
  assert.match(r.stdout, /INV-7/);
});

test('verify-books — --json 은 7종 체크 전부 pass', () => {
  const r = runNode([SCRIPT, '--json']);
  assert.equal(r.status, 0);
  const summary = JSON.parse(r.stdout);
  assert.equal(summary.gate, 'PASS');
  assert.equal(summary.checks.length, 7);
  assert.ok(summary.checks.every((c) => c.pass));
});

test('verify-books — 위반 픽스처는 7종 전부 FAIL (throw 분기 커버, exit 1)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-books-'));
  try {
    // INV-1: 'abc' 비숫자 / INV-7: Q2 차입 음수 (0 은 무차입 경영으로 정상)
    writeFileSync(join(dir, 'trial_balance.csv'), [
      'quarter,sales,accounts_receivable,inventory,contract_liability,operating_income,operating_cash_flow,variable_rate_debt,interest_expense',
      '2026Q1,abc,100,1,1,50,10,1000,10',
      '2026Q2,100,999,1,1,50,10,-500,10',
    ].join('\n'));
    // INV-2: 2026Q1 aging 합 50 ≠ 시산표 100
    writeFileSync(join(dir, 'ar_aging.csv'), [
      'quarter,customer,current,d31_60,d61_90,d90_plus',
      '2026Q1,엑스상사,10,10,10,20',
    ].join('\n'));
    // INV-3: 매출 합 150 ≠ 100 / INV-4: 배부 불비례 / INV-5: 비중 합 90 / INV-6: 영업이익 검산 불일치
    writeFileSync(join(dir, 'cost_allocation.csv'), [
      'product,sales,direct_cost,allocated_common_cost,allocation_basis_sales_pct,actual_machine_hours_pct,operating_income',
      'P1,60,10,30,60,45,99',
      'P2,90,10,30,30,45,50',
    ].join('\n'));

    const json = runNode([SCRIPT, '--json'], { VERIFY_BOOKS_DATA_DIR: dir });
    assert.equal(json.status, 1);
    const summary = JSON.parse(json.stdout);
    assert.equal(summary.gate, 'FAIL');
    assert.equal(summary.checks.filter((c) => !c.pass).length, 7);
    assert.match(summary.checks[0].detail, /숫자가 아님/);

    const human = runNode([SCRIPT], { VERIFY_BOOKS_DATA_DIR: dir });
    assert.equal(human.status, 1);
    assert.match(human.stdout, /GATE FAIL — 7건 위반/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
