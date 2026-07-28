import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';
import { buildJudgePrompt, parseJudgeVerdict, judgeBriefing, resolveProvider } from '../../common/judge.mjs';
import { signalsForDataDir } from '../briefing-eval.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SAMPLE_DIR = join(HERE, '..', '..', 'data', 'sample');
const EVAL_CLI = join(HERE, '..', 'briefing-eval.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

// 규칙 채점을 PASS 하는 브리핑 (briefing-eval self-test GOOD 과 동일 계약).
const GOOD_BRIEFING = `# CEO Briefing — 회계 리스크 진단

## 1. 수익 조기 인식 가능성
결론: 매출은 11.3% 늘었지만 현금이 따라오지 않았습니다.
근거: 매출채권 +46.9%, 계약부채 340→180, 영업현금흐름 880→-310.
왜 문제인가: 팔았다고 기록했지만 현금이 들어오지 않는 매출일 수 있습니다.
확신도: 가설 — 기말 전후 매출 전표와 계약 진행률 확인 필요.
판별 테스트: 기말 ±7일 매출 전표 분포와 계약 진행률 데이터 대조 (재무팀).

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

const verdictJson = (score) => JSON.stringify(['S1', 'S2', 'S3', 'S4'].map((id) => ({
  id, causality: score, decision: score, falsifiability: score, comment: `${id} 심사평`,
})));

test('judge — buildJudgePrompt: 루브릭 3축 + 신호 근거 수치 포함', () => {
  const signals = signalsForDataDir(SAMPLE_DIR).filter((s) => s.present);
  const prompt = buildJudgePrompt('브리핑 본문', signals);
  assert.match(prompt, /causality/);
  assert.match(prompt, /falsifiability/);
  assert.match(prompt, /재배열\/나열에 불과/); // 패킷 복붙 = 0점 앵커
  assert.match(prompt, /S1 수익-현금 괴리/);
  assert.match(prompt, /"arGrowthPct":46\.9/);
});

test('judge — parseJudgeVerdict: 코드펜스 허용·클램핑·누락 id 0점·불량 응답 throw', () => {
  const fenced = '설명입니다\n```json\n' + verdictJson(2) + '\n```';
  const r = parseJudgeVerdict(fenced, ['S1', 'S2', 'S3', 'S4']);
  assert.equal(r.length, 4);
  assert.equal(r[0].causality, 2);

  const partial = parseJudgeVerdict('[{"id":"S1","causality":9,"decision":-3,"falsifiability":1.7,"comment":"x"}]', ['S1', 'S2']);
  assert.equal(partial[0].causality, 2);       // 상한 클램프
  assert.equal(partial[0].decision, 0);        // 하한 클램프
  assert.equal(partial[0].falsifiability, 2);  // 반올림
  assert.match(partial[1].comment, /판정 누락/);

  assert.throws(() => parseJudgeVerdict('JSON 없음', ['S1']), /JSON 배열을 찾지 못함/);
});

test('judge — judgeBriefing(주입 caller): 집계·advisory 등급·프롬프트 수신', async () => {
  const signals = signalsForDataDir(SAMPLE_DIR);
  const prompts = [];
  const good = await judgeBriefing(GOOD_BRIEFING, signals, {
    caller: async (p) => { prompts.push(p); return verdictJson(2); },
  });
  assert.equal(good.advisory, '우수');
  assert.deepEqual(good.averages, { causality: 2, decision: 2, falsifiability: 2 });
  assert.equal(good.perSignal.length, 4);
  assert.match(prompts[0], /브리핑 전문/);

  const copy = await judgeBriefing('패킷 복붙', signals, { caller: async () => verdictJson(0) });
  assert.equal(copy.advisory, '보완 필요');
});

test('judge — skip 경로: PRESENT 0건 / caller 예외 / provider off', async () => {
  const signals = signalsForDataDir(SAMPLE_DIR).map((s) => ({ ...s, present: false }));
  const noTarget = await judgeBriefing('x', signals, { caller: async () => verdictJson(2) });
  assert.equal(noTarget.skipped, true);
  assert.match(noTarget.reason, /PRESENT 신호 없음/);

  const failed = await judgeBriefing('x', signalsForDataDir(SAMPLE_DIR), {
    caller: async () => { throw new Error('HTTP 500'); },
  });
  assert.equal(failed.skipped, true);
  assert.match(failed.reason, /HTTP 500/);

  assert.equal(resolveProvider({ BRIEFING_JUDGE_PROVIDER: 'off' }), null);
  assert.equal(resolveProvider({ BRIEFING_JUDGE_PROVIDER: 'gemini' }), 'gemini');
});

test('briefing-eval CLI — --judge: provider off 는 skip, 규칙 FAIL 은 판정 진입 안 함', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-judge-'));
  try {
    const good = join(dir, 'good.md');
    writeFileSync(good, GOOD_BRIEFING);
    const off = runNode([EVAL_CLI, '--judge', good], { BRIEFING_JUDGE_PROVIDER: 'off' });
    assert.equal(off.status, 0, off.stdout + off.stderr);
    assert.match(off.stdout, /인과 품질 판정/);
    assert.match(off.stdout, /skip {2}LLM 키 없음|skip {2}.*off/);

    const bad = join(dir, 'bad.md');
    writeFileSync(bad, '# 보고\n명백한 분식회계입니다. 확실합니다.\n');
    const failed = runNode([EVAL_CLI, '--judge', bad], { BRIEFING_JUDGE_PROVIDER: 'off' });
    assert.equal(failed.status, 1);
    assert.match(failed.stdout, /규칙 채점 FAIL — 1차부터 통과할 것/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('briefing-eval CLI — --judge: Gemini 스텁으로 판정 섹션 출력 (네트워크 0)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-judge2-'));
  try {
    const good = join(dir, 'good.md');
    writeFileSync(good, GOOD_BRIEFING);
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify({
      rules: [{
        match: 'generativelanguage.googleapis.com',
        json: { candidates: [{ content: { parts: [{ text: verdictJson(2) }] } }] },
      }],
    }));
    const r = runNode([EVAL_CLI, '--judge', good],
      withFetchStub(PRELOAD, stub, { GEMINI_API_KEY: 'test-key', BRIEFING_JUDGE_PROVIDER: 'gemini' }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /EVAL PASS/);
    assert.match(r.stdout, /\[종합\] 우수/);
    assert.match(r.stdout, /provider: gemini/);
    assert.match(r.stdout, /advisory — PASS\/FAIL 판정은 위 규칙 채점이 확정/);

    const j = runNode([EVAL_CLI, '--judge', '--json', good],
      withFetchStub(PRELOAD, stub, { GEMINI_API_KEY: 'test-key', BRIEFING_JUDGE_PROVIDER: 'gemini' }));
    const out = JSON.parse(j.stdout);
    assert.equal(out.judge.advisory, '우수');
    assert.equal(out.pass, true);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
