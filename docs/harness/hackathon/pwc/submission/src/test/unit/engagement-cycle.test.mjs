import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';
import { computeDelta } from '../../bin/engagement-cycle.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CLI = join(HERE, '..', '..', 'bin', 'engagement-cycle.mjs');

const BRIEFING = `# 한빛커머스 CEO 리스크 브리핑

## 리스크 1 — 수익-현금 괴리 (E1)
결론: 매출과 현금이 반대로 움직입니다.
근거: 괴리 35.7%p.
확신도: 가설 — 전표 확인 필요.
판별 테스트: 기말 전표 대조.
권고 조치: 기말 ±7일 전표 분포를 재무팀이 이번 주 내 대조.

## 리스크 2 — 공시 행간 (E5)
결론: 정정 공시가 반복됩니다.
근거: 정정 3건.
확신도: 확인됨 — 공시 목록 기준.
판별 테스트: 정정 사유 분류.
권고 조치: IR 이 정정 3건을 유형 분류해 보고.

## 확인 범위와 한계
- 외부 공시 기반.
`;

const PACKET_BEFORE = {
  corp: { name: '한빛커머스(주)' },
  signals: [
    { id: 'E1', name: '수익-채권 괴리', present: true, evaluable: true, evidence: { gapPp: 35.7, ocfToOperatingIncome: 0.4 }, markers: [], categoryPattern: 'x' },
    { id: 'E5', name: '공시 행간', present: true, evaluable: true, evidence: { corrections: 3 }, markers: [], categoryPattern: 'x' },
    { id: 'E4', name: '유동성', present: false, evaluable: true, evidence: { currentRatioPct: '150→140→135' }, markers: [], categoryPattern: 'x' },
  ],
};
const PACKET_AFTER = {
  corp: { name: '한빛커머스(주)' },
  signals: [
    { id: 'E1', name: '수익-채권 괴리', present: false, evaluable: true, evidence: { gapPp: 4.1, ocfToOperatingIncome: 1.2 }, markers: [], categoryPattern: 'x' },
    { id: 'E5', name: '공시 행간', present: true, evaluable: true, evidence: { corrections: 5 }, markers: [], categoryPattern: 'x' },
    { id: 'E4', name: '유동성', present: true, evaluable: true, evidence: { currentRatioPct: '140→135→104' }, markers: [], categoryPattern: 'x' },
  ],
};

function makePipelineDir(root, name, packet = PACKET_BEFORE, briefing = BRIEFING) {
  const dir = join(root, name);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'briefing.md'), briefing);
  writeFileSync(join(dir, 'diagnostic-packet.json'), JSON.stringify(packet, null, 2));
  return dir;
}

// ── computeDelta (순수 함수) ─────────────────────────────────
test('computeDelta — 신규/해소/지속 분류 + 근거 수치 diff', () => {
  const d = computeDelta(PACKET_BEFORE, PACKET_AFTER);
  assert.deepEqual(d.newRisks.map((r) => r.id), ['E4']);      // absent → PRESENT
  assert.deepEqual(d.resolved.map((r) => r.id), ['E1']);      // PRESENT → absent
  assert.deepEqual(d.persisting.map((r) => r.id), ['E5']);    // PRESENT → PRESENT
  const e1 = d.rows.find((r) => r.id === 'E1');
  assert.ok(e1.evidenceDiff.some((x) => x.key === 'gapPp' && x.before === 35.7 && x.after === 4.1));
  const e5 = d.rows.find((r) => r.id === 'E5');
  assert.equal(e5.changed, false);
  assert.ok(e5.evidenceDiff.some((x) => x.key === 'corrections')); // 지속이어도 수치 변화는 잡는다
});

test('computeDelta — 한쪽에만 있는 신호는 N/A 로 대사', () => {
  const d = computeDelta({ signals: [] }, PACKET_AFTER);
  assert.equal(d.rows.find((r) => r.id === 'E4').before, 'N/A');
  assert.deepEqual(d.newRisks.map((r) => r.id), ['E4', 'E5']);
});

// ── CLI 전 구간: init → note 게이트 → delta → next-cycle ─────
test('engagement-cycle CLI — 사이클 전 구간 상태머신과 게이트', () => {
  const root = mkdtempSync(join(tmpdir(), 'tca-eng-'));
  try {
    const pipe1 = makePipelineDir(root, 'out-c1');
    const engRoot = join(root, 'engagements');

    // init: 브리핑 권고 조치 → 액션 2건 파생
    const init = runNode([CLI, 'init', '--from', pipe1, '--dir', engRoot]);
    assert.equal(init.status, 0, init.stdout + init.stderr);
    assert.match(init.stdout, /추적 액션 2건/);
    const engDir = join(engRoot, '한빛커머스-주');
    const state0 = JSON.parse(readFileSync(join(engDir, 'engagement.json'), 'utf8'));
    assert.equal(state0.cycle, 1);
    assert.equal(state0.phase, 'delivered');
    assert.equal(state0.actions.length, 2);
    assert.match(state0.actions[0].action, /전표 분포/);

    // 중복 init 금지
    assert.equal(runNode([CLI, 'init', '--from', pipe1, '--dir', engRoot]).status, 1);

    // 순서 위반 전이 차단: delivered 에서 review 로 점프 불가
    const jump = runNode([CLI, 'advance', '--engagement', engDir, '--to', 'review']);
    assert.equal(jump.status, 1);
    assert.match(jump.stderr, /전이 불가/);

    // follow-up 진입 → 노트 없이 review 게이트 실패
    assert.equal(runNode([CLI, 'advance', '--engagement', engDir, '--to', 'follow-up']).status, 0);
    const blocked = runNode([CLI, 'advance', '--engagement', engDir, '--to', 'review']);
    assert.equal(blocked.status, 1);
    assert.match(blocked.stderr, /이행 노트 없는 액션 2건/);

    // 액션별 이행 노트 기록 → review 진입
    runNode([CLI, 'note', '--engagement', engDir, '--action', '1', '--status', 'done', '--note', '재무팀 전표 대조 완료 — 특이 없음']);
    runNode([CLI, 'note', '--engagement', engDir, '--action', '2', '--status', 'blocked', '--note', 'IR 담당 공석']);
    const status = runNode([CLI, 'status', '--engagement', engDir]);
    assert.match(status.stdout, /\[done\s*\] #1/);
    assert.match(status.stdout, /\[blocked\s*\] #2/);
    assert.equal(runNode([CLI, 'advance', '--engagement', engDir, '--to', 'review']).status, 0);

    // delta: 재진단 패킷과 결정론 비교
    const packet2 = join(root, 'packet-c2.json');
    writeFileSync(packet2, JSON.stringify(PACKET_AFTER));
    const deltaPath = join(engDir, 'delta-cycle-1.md');
    const delta = runNode([CLI, 'delta', '--engagement', engDir, '--packet', packet2, '--out', deltaPath]);
    assert.equal(delta.status, 0, delta.stdout + delta.stderr);
    assert.match(delta.stdout, /신규 1 · 해소 1 · 지속 1/);
    const deltaMd = readFileSync(deltaPath, 'utf8');
    assert.match(deltaMd, /E4 유동성: absent → PRESENT/);
    assert.match(deltaMd, /gapPp: 35.7 → 4.1/);
    assert.match(deltaMd, /해소가 곧 "조치 덕분"은 아니며/); // 인과 단정 금지 문구
    const deltaJson = runNode([CLI, 'delta', '--engagement', engDir, '--packet', packet2, '--json']);
    assert.equal(JSON.parse(deltaJson.stdout).resolved[0].id, 'E1');

    // retro 게이트: 델타 파일 필수
    assert.equal(runNode([CLI, 'advance', '--engagement', engDir, '--to', 'retro']).status, 1);
    assert.equal(runNode([CLI, 'advance', '--engagement', engDir, '--to', 'retro', '--delta-file', deltaPath]).status, 0);

    // next-cycle 게이트: 회고 + 새 브리핑 산출 폴더 필수 → cycle 2 개시
    assert.equal(runNode([CLI, 'advance', '--engagement', engDir, '--to', 'next-cycle']).status, 1);
    const retroPath = join(engDir, 'retro-cycle-1.md');
    writeFileSync(retroPath, '# 회고\n- 바꿀 것 1가지: E5 판별 테스트 문구 개선');
    const pipe2 = makePipelineDir(root, 'out-c2', PACKET_AFTER);
    const next = runNode([CLI, 'advance', '--engagement', engDir, '--to', 'next-cycle', '--retro', retroPath, '--from', pipe2]);
    assert.equal(next.status, 0, next.stdout + next.stderr);
    assert.match(next.stdout, /cycle 2 개시/);
    const state1 = JSON.parse(readFileSync(join(engDir, 'engagement.json'), 'utf8'));
    assert.equal(state1.cycle, 2);
    assert.equal(state1.phase, 'delivered');
    assert.equal(state1.history.length, 1);
    assert.equal(state1.history[0].cycle, 1);
    assert.equal(state1.history[0].actions.length, 2);          // 전 사이클 이행 기록 보존
    assert.match(state1.baseline.packetPath, /out-c2/);          // 새 기준선으로 교체
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('engagement-cycle CLI — init: 음성 브리핑(리스크 0건)은 액션 0건 + 재진단 중심 안내', () => {
  const root = mkdtempSync(join(tmpdir(), 'tca-eng2-'));
  try {
    const negBriefing = '# 점검 결과\n## 확인 범위와 한계\n결론: 확인된 경영리스크는 포착되지 않았습니다.\n';
    const pipe = makePipelineDir(root, 'out-neg', { corp: { name: '건전상사' }, signals: [] }, negBriefing);
    const r = runNode([CLI, 'init', '--from', pipe, '--dir', join(root, 'eng')]);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /추적 액션 0건/);
    assert.match(r.stdout, /분기 재진단\(review\) 중심/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('engagement-cycle CLI — 사용법/오류 경로', () => {
  assert.equal(runNode([CLI]).status, 1);                      // cmd 없음 → 사용법 + exit 1
  assert.equal(runNode([CLI, '--help']).status, 0);            // --help 는 관례대로 exit 0
  assert.equal(runNode([CLI, 'status', '--help']).status, 0);
  const missing = runNode([CLI, 'status', '--engagement', 'no/such/dir']);
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /먼저 init/);
});
