import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DOCTOR_CLI = join(HERE, '..', '..', 'bin', 'doctor.mjs');

test('doctor — --json: 셀프테스트 게이트 PASS + 내부 신호 4종 + MCP 배선 확인 (네트워크 0)', () => {
  const r = runNode([DOCTOR_CLI, '--json']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  const report = JSON.parse(r.stdout);
  assert.equal(report.node.ok, true);
  assert.equal(report.selfTest.ok, true);
  assert.equal(report.selfTest.gate, 'PASS');
  assert.deepEqual(report.selfTest.presentSignals, ['S1', 'S2', 'S3', 'S4']);
  assert.equal(report.mcp.ok, true);
  assert.equal(report.mcp.servers.length, 4);
  assert.ok(report.mcp.servers.every((s) => s.exists), JSON.stringify(report.mcp.servers));
  // 키 상태는 환경 의존이므로 구조만 검증 (found 는 boolean)
  assert.ok(report.keys.length >= 6);
  assert.ok(report.keys.every((k) => typeof k.found === 'boolean' && k.axis && k.without));
  assert.equal(report.ready.offlineDemo, true);
  assert.equal(report.ready.broken, false);
});

test('doctor — 사람용 리포트: 진단 결과 + 다음 명령 안내 포함', () => {
  const r = runNode([DOCTOR_CLI]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /doctor — 환경 진단/);
  assert.match(r.stdout, /오프라인 셀프테스트 — 게이트 PASS/);
  assert.match(r.stdout, /지금 바로 실행 가능한 것/);
  assert.match(r.stdout, /demo-e2e\.mjs --agent none/);
  assert.match(r.stdout, /진단 결과: 정상/);
});
