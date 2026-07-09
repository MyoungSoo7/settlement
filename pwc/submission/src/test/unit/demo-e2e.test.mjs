import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEMO_CLI = join(HERE, '..', '..', 'bin', 'demo-e2e.mjs');
const FAKE_AGENT = join(HERE, 'helpers', 'fake-agent.mjs');

// 샘플 파생 신호 4종을 충족하는 브리핑 (셀프테스트 GOOD 계약과 동일).
const GOOD_BRIEFING = `# CEO Briefing — 회계 리스크 진단

## 1. 수익 조기 인식 가능성
결론: 매출은 11.3% 늘었지만 현금이 따라오지 않았습니다.
근거: 매출채권 +46.9%, 계약부채 340→180, 영업현금흐름 880→-310.
확신도: 가설 — 기말 전후 매출 전표 확인 필요.
판별 테스트: 기말 ±7일 매출 전표 분포와 계약 진행률 대조 (재무팀).

## 2. 특정 거래처 신용 집중
결론: 장기 미수가 거래처 한 곳에 쏠려 있습니다.
근거: 90일 초과 채권 중 에이프릴리테일 1,750, 비중 91.1%.
확신도: 가설 — 여신 한도·담보 설정 내역 확인 필요.
판별 테스트: 에이프릴리테일 여신계약·담보등기 대조 (영업팀).

## 3. 원가 배분 왜곡 — 제품C
결론: 제품C는 자원 소비 기준 재배부 시 적자로 뒤집힙니다.
근거: 기계시간 45% 기준 재배부 시 공통원가 1,350, 영업손익 +20 → -880.
확신도: 확인됨 — 배부 기준 재계산은 산술로 확정됩니다.
판별 테스트: 기계시간 로그 원천 데이터로 배부율 재산정 검증 (원가팀).

## 4. 차입 의존 성장·금리 노출
결론: 영업현금 악화를 변동금리 차입으로 메우고 있습니다.
근거: 변동금리 차입 14,500, 이자비용 90→320, 이자보상배율 23.9→8.9배.
확신도: 가설 — 고정금리 전환·헤지 계약 여부 확인 필요.
판별 테스트: 차입 약정서 금리 조건과 헤지 계약 현황 대조 (재무팀).

## 확인 범위와 한계
재고 회전과 판관비는 검토했고 특이사항 없었습니다.
권고 조치: 위 4건을 각 팀에 배정해 1주 내 판별 테스트 결과 회신.
`;

test('demo-e2e — --agent none: 게이트·신호까지 실행 후 수동 절차 안내 (exit 0)', () => {
  const out = mkdtempSync(join(tmpdir(), 'tca-demo-'));
  try {
    const r = runNode([DEMO_CLI, '--agent', 'none', '--out', out]);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /\[1\] 불변식 게이트/);
    assert.match(r.stdout, /GATE PASS/);
    assert.match(r.stdout, /\[2\] 신호 파생/);
    assert.match(r.stdout, /수동 절차로 전환/);
    assert.ok(existsSync(join(out, 'prompt.txt')));
    assert.ok(existsSync(join(out, 'packet.json')));
    // 프롬프트에는 패킷과 오탐 금지 규칙이 들어 있어야 한다.
    const prompt = readFileSync(join(out, 'prompt.txt'), 'utf8');
    assert.match(prompt, /PRESENT 신호만 리스크로 다룬다/);
    assert.match(prompt, /"presentCount": 4/);
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test('demo-e2e — 가짜 에이전트로 전 구간: 게이트→신호→브리핑→채점 EVAL PASS (exit 0)', () => {
  const out = mkdtempSync(join(tmpdir(), 'tca-demo2-'));
  try {
    const briefingFixture = join(out, 'fixture.md');
    writeFileSync(briefingFixture, GOOD_BRIEFING);
    const r = runNode([DEMO_CLI, '--agent', `node ${FAKE_AGENT}`, '--out', out],
      { FAKE_BRIEFING_FILE: briefingFixture, BRIEFING_JUDGE_PROVIDER: 'off' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /\[3\] 에이전트 브리핑 생성/);
    assert.match(r.stdout, /브리핑 저장:/);
    assert.match(r.stdout, /\[재현율\] 4\/4/);
    assert.match(r.stdout, /EVAL PASS/);
    assert.ok(existsSync(join(out, 'briefing.md')));
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test('demo-e2e — 게이트 FAIL 데이터: 추론 진입 전에 중단 (exit 1)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-demo3-'));
  try {
    writeFileSync(join(dir, 'trial_balance.csv'), [
      'quarter,sales,accounts_receivable,inventory,contract_liability,operating_income,operating_cash_flow,variable_rate_debt,interest_expense',
      '2026Q2,100,999,1,1,50,10,30,2',
    ].join('\n'));
    writeFileSync(join(dir, 'ar_aging.csv'), 'quarter,customer,current,d31_60,d61_90,d90_plus\n2026Q2,가,1,1,1,1\n');
    writeFileSync(join(dir, 'cost_allocation.csv'), [
      'product,sales,direct_cost,allocated_common_cost,allocation_basis_sales_pct,actual_machine_hours_pct,operating_income',
      'P1,100,50,30,100,100,20',
    ].join('\n'));
    const r = runNode([DEMO_CLI, '--data-dir', dir, '--agent', 'none', '--out', join(dir, 'out')]);
    assert.equal(r.status, 1);
    assert.match(r.stdout, /GATE FAIL/);
    assert.match(r.stderr, /DEMO 중단/);
    assert.ok(!existsSync(join(dir, 'out', 'briefing.md')));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
