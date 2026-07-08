import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';
import { evaluateBriefing, splitSections } from '../briefing-eval.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SCRIPT = join(HERE, '..', 'briefing-eval.mjs');

// ceo-briefing SKILL 계약을 충족하는 브리핑: 리스크당 결론·근거·확신도·판별 테스트.
const GOOD_BRIEFING = `# CEO Briefing — 2026Q2 회계 리스크 진단

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

test('splitSections — heading 기준 분할, 빈 섹션 제거', () => {
  const secs = splitSections('# A\n본문1\n## B\n본문2\n');
  assert.equal(secs.length, 2);
  assert.equal(secs[0].heading, 'A');
  assert.equal(secs[1].heading, 'B');
});

test('evaluateBriefing — 계약 충족 브리핑은 전 축 PASS', () => {
  const r = evaluateBriefing(GOOD_BRIEFING);
  assert.equal(r.recall, 4);
  assert.equal(r.total, 4);
  assert.deepEqual(r.violations, []);
  assert.deepEqual(r.missingSections, []);
  assert.deepEqual(r.calibrationMisses, []);
  assert.deepEqual(r.discriminationMisses, []);
  assert.deepEqual(r.overClaims, []);
  assert.equal(r.pass, true);
});

test('evaluateBriefing — 단정 표현·섹션 누락·신호 미포착 감지', () => {
  const r = evaluateBriefing('매출채권 급증은 명백한 분식회계입니다. 확실합니다. 틀림없다. 단언컨대 그렇다.');
  assert.ok(r.recall < 4);
  assert.ok(r.violations.includes('분식 단정'));
  assert.ok(r.violations.includes('확실 단정'));
  assert.ok(r.violations.includes('명백 단정'));
  assert.ok(r.violations.includes('틀림없다 단정'));
  assert.ok(r.violations.includes('단언 표현'));
  assert.ok(r.missingSections.includes('확신도'));
  assert.equal(r.pass, false);
});

test('evaluateBriefing — 신호당 마커 1개로는 미포착(detected=false)', () => {
  const r = evaluateBriefing('결론 근거 확신도 권고 조치. 이자보상배율만 언급.');
  const s4 = r.signals.find((s) => s.id === 'S4');
  assert.equal(s4.hitCount, 1);
  assert.equal(s4.detected, false);
});

test('근접성 — 마커를 여러 섹션에 흩뿌리면 미포착', () => {
  const scattered = `# 보고
## A
매출채권 46.9% 증가.
## B
매출 11.3% 증가.
## C
영업현금흐름 -310 전환.
## D
계약부채 340→180 확인.
`;
  const r = evaluateBriefing(scattered);
  const s1 = r.signals.find((s) => s.id === 'S1');
  assert.equal(s1.detected, false); // 한 섹션에 2개 이상 모이지 않음
  assert.ok(r.recall < 4);
  assert.equal(r.pass, false);
});

test('확신도 보정 — 포착 신호 섹션에 확신도 태그 없으면 calibrationMiss + FAIL', () => {
  // S1 섹션: 마커 2개 이상 + 판별 테스트는 있으나 확신도 태그 없음.
  const noTag = `# B
## 1. 수익
근거: 매출채권 46.9%, 매출 11.3%, 영업현금흐름 -310, 계약부채 감소.
판별 테스트: 기말 전표 대조.
## 기타
결론: 참고. 확신도: 가설. 권고 조치: 없음.
`;
  const r = evaluateBriefing(noTag);
  const s1 = r.signals.find((s) => s.id === 'S1');
  assert.equal(s1.detected, true);
  assert.equal(s1.confidenceTagged, false);
  assert.ok(r.calibrationMisses.includes('S1'));
  assert.equal(r.pass, false);
});

test('판별 테스트 — 포착 신호 섹션에 확인 절차 없으면 discriminationMiss + FAIL', () => {
  // S2 섹션: 마커 2개 이상 + 확신도 태그는 있으나 판별 테스트 없음.
  const noTest = `# B
## 2. 거래처
근거: 에이프릴리테일 1,750, 91.1% 집중.
확신도: 가설.
## 기타
결론: 참고. 권고 조치: 없음.
`;
  const r = evaluateBriefing(noTest);
  const s2 = r.signals.find((s) => s.id === 'S2');
  assert.equal(s2.detected, true);
  assert.equal(s2.hasDiscriminationTest, false);
  assert.ok(r.discriminationMisses.includes('S2'));
  assert.equal(r.pass, false);
});

test('정밀도 — 근거 없는 확인됨 리스크는 과잉 확신으로 FAIL', () => {
  const overclaim = GOOD_BRIEFING + `
## 5. 세무 추징 리스크
결론: 대규모 추징이 있을 것으로 확인됨.
확신도: 확인됨.
`;
  const r = evaluateBriefing(overclaim);
  assert.equal(r.recall, 4); // 진짜 4신호는 여전히 포착
  assert.ok(r.overClaims.length >= 1);
  assert.equal(r.pass, false); // 그러나 지어낸 확정 리스크로 FAIL
});

test('clean 모드 — 이상 없는 데이터의 정상 브리핑은 PASS', () => {
  const cleanGood = `# 점검 결과
## 확인 범위
결론: 유의미한 이상 신호가 확인되지 않았습니다.
근거: aging 합계가 시산표와 일치합니다.
확신도: 확인됨 — GATE PASS.
권고 조치: 동일 점검 유지.
`;
  const r = evaluateBriefing(cleanGood, { mode: 'clean' });
  assert.equal(r.falseSignals.length, 0);
  assert.equal(r.cleanAck, true);
  assert.equal(r.pass, true);
});

test('clean 모드 — 이상 없는데 신호를 지어내면 FAIL(오탐+과잉확신)', () => {
  const cleanBad = `# 보고
## 1. 원가 손실
근거: 제품C -880 적자, 미수 91.1% 에이프릴리테일 집중.
확신도: 확인됨.
## 2. 세무 리스크
결론: 세금 추징이 있을 것으로 확인됨.
확신도: 확인됨.
`;
  const r = evaluateBriefing(cleanBad, { mode: 'clean' });
  assert.ok(r.falseSignals.length >= 1);
  assert.ok(r.overClaims.length >= 1);
  assert.equal(r.pass, false);
});

test('CLI — --self-test 는 ALL GREEN (exit 0)', () => {
  const r = runNode([SCRIPT, '--self-test']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /ALL GREEN/);
});

test('CLI — 브리핑 파일 채점: PASS / FAIL / clean / 인자 없음', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-brief-'));
  try {
    const goodFile = join(dir, 'good.md');
    const badFile = join(dir, 'bad.md');
    const cleanFile = join(dir, 'clean.md');
    writeFileSync(goodFile, GOOD_BRIEFING);
    writeFileSync(badFile, '# 보고\n명백한 분식회계입니다. 확실합니다.\n');
    writeFileSync(cleanFile, '# 점검\n결론: 이상 없음.\n확신도: 확인됨.\n');

    const good = runNode([SCRIPT, goodFile]);
    assert.equal(good.status, 0);
    assert.match(good.stdout, /EVAL PASS/);
    assert.match(good.stdout, /\[재현율\] 4\/4/);

    const bad = runNode([SCRIPT, badFile]);
    assert.equal(bad.status, 1);
    assert.match(bad.stdout, /EVAL FAIL/);
    assert.match(bad.stdout, /MISS/);
    assert.match(bad.stdout, /단정 표현/);

    const clean = runNode([SCRIPT, '--clean', cleanFile]);
    assert.equal(clean.status, 0);
    assert.match(clean.stdout, /clean/);
    assert.match(clean.stdout, /EVAL PASS/);

    const noArg = runNode([SCRIPT]);
    assert.equal(noArg.status, 2);
    assert.match(noArg.stderr, /사용법/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
