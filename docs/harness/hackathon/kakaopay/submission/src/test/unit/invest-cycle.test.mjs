/**
 * invest-cycle CLI 단위테스트 — 상태머신 게이트가 실제로 exit 1 로 차단하는지 검증.
 * (스킬의 프롬프트 규율이 아니라 CLI 가 전이를 소유한다는 계약의 회귀 방어선.)
 * 실행: node src/test/run-all.mjs (개별: node src/test/unit/invest-cycle.test.mjs)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CLI = join(HERE, '..', '..', 'bin', 'invest-cycle.mjs');

const workDir = mkdtempSync(join(tmpdir(), 'kic-cycle-'));
test.after(() => rmSync(workDir, { recursive: true, force: true }));

const run = (...args) => runNode([CLI, ...args, '--dir', workDir]);
const state = () => JSON.parse(readFileSync(join(workDir, 'state.json'), 'utf8'));
const portfolio = () => JSON.parse(readFileSync(join(workDir, 'portfolio.json'), 'utf8'));

test('invest-cycle CLI — 전체 사이클 1바퀴: 게이트 차단과 정상 전이', () => {
  // init 게이트: 예산 없이 거부
  assert.equal(run('init').status, 1);

  // init 성공 → P1-select, 파일 2개 생성 (--weeks 1 로 추적 창 축소)
  const init = run('init', '--budget', '3000000', '--weeks', '1');
  assert.equal(init.status, 0, init.stderr);
  assert.ok(existsSync(join(workDir, 'state.json')));
  assert.ok(existsSync(join(workDir, 'portfolio.json')));
  assert.equal(state().phase, 'P1-select');
  assert.equal(run('init', '--budget', '1000000').status, 1);   // 중복 init 거부

  // P2 게이트: watchlist 비어 있으면 거부
  const p2Fail = run('advance', '--to', 'P2');
  assert.equal(p2Fail.status, 1);
  assert.match(p2Fail.stderr, /watchlist/);

  // 단계 건너뛰기 거부 (P1 에서 P3 진입 불가)
  assert.equal(run('advance', '--to', 'P3').status, 1);

  // watch 기록 → P2 진입
  assert.equal(run('watch', '--code', '105560', '--name', 'KB금융', '--basis', '규칙 5종 통과').status, 0);
  assert.equal(run('watch', '--code', '105560', '--name', 'KB금융').status, 1);   // 중복 watch 거부
  assert.equal(run('advance', '--to', 'P2').status, 0);
  assert.equal(state().phase, 'P2-dryrun');

  // P3 게이트: 손절·익절 확정 holdings 없으면 거부, 역전된 손절가도 거부
  const p3Fail = run('advance', '--to', 'P3');
  assert.equal(p3Fail.status, 1);
  assert.match(p3Fail.stderr, /팔 기준/);
  assert.equal(run('plan', '--code', '105560', '--name', 'KB금융', '--entry', '90000', '--stop', '95000', '--take', '108000').status, 1);
  assert.equal(run('plan', '--code', '105560', '--name', 'KB금융', '--entry', '90000', '--stop', '83700', '--take', '108000').status, 0);
  assert.equal(portfolio().holdings[0].plan.stopLoss, 83700);
  assert.equal(portfolio().watchlist.length, 0);                 // watchlist → holdings 이동
  assert.equal(run('advance', '--to', 'P3').status, 0);

  // P4 게이트: 주간 점검(check) 없이 거부 → check 로 주차 채우면 통과
  const p4Fail = run('advance', '--to', 'P4');
  assert.equal(p4Fail.status, 1);
  assert.match(p4Fail.stderr, /0\/1주차/);
  assert.equal(run('check', '--summary', '손절선·비중·악재 이상 없음').status, 0);
  assert.equal(state().week, 1);
  assert.equal(run('advance', '--to', 'P4').status, 0);
  assert.equal(run('check').status, 1);                          // P3 밖에서 check 거부

  // P5 게이트: 리포트 파일 실재 필수
  assert.equal(run('advance', '--to', 'P5').status, 1);
  const report = join(workDir, 'result-test.md');
  writeFileSync(report, '# 주식 투자 리포트\n');
  assert.equal(run('advance', '--to', 'P5', '--report', report).status, 0);

  // next-cycle 게이트: 규칙 0개면 거부 → rule 기록 후 cycle 2 로
  const ncFail = run('advance', '--to', 'next-cycle');
  assert.equal(ncFail.status, 1);
  assert.match(ncFail.stderr, /규칙/);
  assert.equal(run('rule', '--add', '3거래일 연속 상승 중 신규 매수 금지').status, 0);
  assert.equal(run('advance', '--to', 'next-cycle').status, 0);
  const s = state();
  assert.equal(s.cycle, 2);
  assert.equal(s.phase, 'P1-select');
  assert.equal(s.week, 0);
  assert.equal(s.rules.length, 1);                               // 규칙은 다음 사이클로 승계

  // status 는 항상 동작 (스킬의 첫 호출 계약)
  const status = run('status');
  assert.equal(status.status, 0);
  assert.match(status.stdout, /사이클 2/);
});

test('invest-cycle CLI — cycle 2: 승계 규칙으로는 회고 게이트를 통과할 수 없다', () => {
  // 앞 테스트에서 cycle 2 · P1-select · 승계 규칙 1개 상태를 이어받는다
  assert.equal(state().cycle, 2);
  assert.equal(state().rules.length, 1);

  // cycle 2 를 P5 까지 진행 (holdings 의 손절·익절 계획은 대장에 승계되어 있음)
  assert.equal(run('watch', '--code', '000660', '--name', 'SK하이닉스', '--basis', '규칙 통과').status, 0);
  assert.equal(run('advance', '--to', 'P2').status, 0);
  assert.equal(run('plan', '--code', '000660', '--name', 'SK하이닉스', '--entry', '200000', '--stop', '186000', '--take', '240000').status, 0);
  assert.equal(run('advance', '--to', 'P3').status, 0);
  assert.equal(run('check', '--summary', '이상 없음').status, 0);
  assert.equal(run('advance', '--to', 'P4').status, 0);
  const report = join(workDir, 'result-cycle2.md');
  writeFileSync(report, '# 주식 투자 리포트 (cycle 2)\n');
  assert.equal(run('advance', '--to', 'P5', '--report', report).status, 0);

  // 핵심 회귀: cycle 1 승계 규칙(1개)이 있어도 "이번 사이클" 규칙 0개면 게이트 차단
  const ncFail = run('advance', '--to', 'next-cycle');
  assert.equal(ncFail.status, 1);
  assert.match(ncFail.stderr, /이번 사이클/);

  // 사이클당 규칙 상한 2개 — 누적(승계 포함)이 아니라 이번 사이클 기준
  assert.equal(run('rule', '--add', '추가 매수는 직전 수량 이하로').status, 0);
  assert.equal(run('rule', '--add', '종목당 비중 20% 상한').status, 0);
  const capFail = run('rule', '--add', '세 번째 규칙');
  assert.equal(capFail.status, 1);
  assert.match(capFail.stderr, /2개까지/);

  assert.equal(run('advance', '--to', 'next-cycle').status, 0);
  assert.equal(state().cycle, 3);
  assert.equal(state().rules.length, 3);   // 누적 승계 (1 + 2)
});

test('invest-cycle CLI — 손상된 state.json 은 명확한 메시지로 거부', () => {
  const dir = mkdtempSync(join(tmpdir(), 'kic-corrupt-'));
  try {
    writeFileSync(join(dir, 'state.json'), '{ broken');
    const res = runNode([CLI, 'status', '--dir', dir]);
    assert.equal(res.status, 1);
    assert.match(res.stderr, /손상/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
