/**
 * LLM Judge — 브리핑의 "인과 설명 품질"을 판정하는 2차 채점 층 (advisory).
 *
 * 계층화 원칙 (게이트 철학의 채점기 적용): 기계로 확정 가능한 것(마커 재현율·오탐·구조·
 * 표현 안전성)은 1차 규칙 채점이 결정론으로 확정하고, PASS 한 브리핑에 대해서만
 * 이 Judge 가 "왜 문제인가"의 서술 품질을 판정한다.
 *
 * 설계 결정:
 * - **advisory**: Judge 는 점수·코멘트만 낸다. PASS/FAIL 판정권은 규칙 채점이 유지한다 —
 *   LLM 의 비결정성이 게이트를 오염시키지 않는다.
 * - **zero-dependency**: Gemini/Anthropic API 를 fetch 로 직접 호출. 키가 없으면 조용히
 *   skip (규칙 채점만으로 기존 경험 무손상).
 * - **주입점**: judgeBriefing 의 caller 인자로 LLM 호출을 대체할 수 있어 네트워크 0 테스트 가능.
 *
 * 루브릭 (신호당 3축 × 0~2점):
 *   causality      — 수치 나열이 아니라 원인→결과 사슬인가 (진단 패킷 수치의 재배열 = 0점)
 *   decision       — CEO 가 "무엇을 결정해야 하는지"가 도출되는가
 *   falsifiability — 판별 테스트가 그 가설을 실제로 판별(반증)할 수 있는 설계인가
 *
 * env:
 *   GEMINI_API_KEY / ANTHROPIC_API_KEY  (상위 .env 폴백 — findEnvKey)
 *   BRIEFING_JUDGE_PROVIDER = off | gemini | anthropic  (강제 지정/비활성)
 *   GEMINI_JUDGE_MODEL (기본 gemini-2.0-flash) / ANTHROPIC_JUDGE_MODEL (기본 claude-haiku-4-5-20251001)
 */
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findEnvKey, safeErrorMessage } from './env.mjs';

const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const REQUEST_TIMEOUT_MS = 45_000;

export function resolveProvider(env = process.env) {
  const forced = (env.BRIEFING_JUDGE_PROVIDER ?? '').toLowerCase();
  if (forced === 'off') return null;
  if (forced === 'gemini' || forced === 'anthropic') return forced;
  if (findEnvKey('GEMINI_API_KEY', MODULE_DIR)) return 'gemini';
  if (findEnvKey('ANTHROPIC_API_KEY', MODULE_DIR)) return 'anthropic';
  return null;
}

/** 판정 프롬프트 — PRESENT 신호의 근거 수치(패킷)와 브리핑 전문을 함께 준다. */
export function buildJudgePrompt(briefingText, presentSignals) {
  const signalBlock = presentSignals
    .map((s) => `- ${s.id} ${s.name}\n  근거 수치(진단 패킷): ${JSON.stringify(s.evidence)}`)
    .join('\n');
  return `당신은 회계법인의 품질관리 심리실(EQR) 검토자다. 아래 CEO 브리핑이 각 리스크 신호를
"논리적 인과"로 설명했는지 판정하라. 숫자의 정확성은 이미 기계 채점이 확정했으므로 다시 보지 마라.

[판정 대상 신호와 근거 수치]
${signalBlock}

[루브릭 — 신호당 3축, 각 0~2점 정수]
- causality: "왜 문제인가"가 원인→결과 사슬로 서술됐는가.
  0 = 근거 수치의 재배열/나열에 불과 (예: "매출채권이 46.9% 늘었고 현금흐름이 -310이다" 로 끝),
  1 = 부분적 인과 (한 단계 연결), 2 = 다단 인과가 경영 결과까지 이어짐 (예: "회수 없는 매출 →
  운전자본 압박 → 차입 증가 → 이자 부담" 처럼 연쇄가 명시됨).
- decision: CEO 가 이 섹션만 읽고 "무엇을 결정/지시해야 하는지"가 나오는가.
  0 = 없음, 1 = 막연한 권고("확인 필요"), 2 = 담당·기한·행동이 특정됨.
- falsifiability: 판별 테스트가 가설을 실제로 판별(반증 가능)하는 설계인가.
  0 = 없음/동어반복, 1 = 관련 자료 확인 수준, 2 = 그 결과에 따라 가설이 기각될 수 있는 구체적 대조.

[출력 형식 — 아래 JSON 배열만, 다른 텍스트 금지]
[{"id":"S1","causality":0,"decision":0,"falsifiability":0,"comment":"한 문장 심사평"}]

[브리핑 전문]
${briefingText}`;
}

/** LLM 응답에서 JSON 배열을 복원한다 (코드펜스/전후 텍스트 허용). */
export function parseJudgeVerdict(text, expectedIds) {
  const match = String(text).match(/\[[\s\S]*\]/);
  if (!match) throw new Error(`Judge 응답에서 JSON 배열을 찾지 못함: ${String(text).slice(0, 120)}`);
  let arr;
  try {
    arr = JSON.parse(match[0]);
  } catch (e) {
    throw new Error(`Judge JSON 파싱 실패: ${e.message}`);
  }
  if (!Array.isArray(arr)) throw new Error('Judge 응답이 배열이 아님');
  const clamp = (v) => Math.max(0, Math.min(2, Math.round(Number(v) || 0)));
  const byId = new Map(arr.map((r) => [r.id, r]));
  return expectedIds.map((id) => {
    const r = byId.get(id);
    if (!r) return { id, causality: 0, decision: 0, falsifiability: 0, comment: '판정 누락 — 0점 처리' };
    return {
      id,
      causality: clamp(r.causality),
      decision: clamp(r.decision),
      falsifiability: clamp(r.falsifiability),
      comment: String(r.comment ?? '').slice(0, 300),
    };
  });
}

async function callGemini(prompt) {
  const key = findEnvKey('GEMINI_API_KEY', MODULE_DIR);
  if (!key) throw new Error('GEMINI_API_KEY 없음');
  // 기본 모델은 이 저장소 ai-service 가 실운영으로 검증한 좌표와 동일 (application.yml)
  const model = process.env.GEMINI_JUDGE_MODEL || 'gemini-2.5-flash';
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${key}`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  if (!res.ok) throw new Error(`Gemini HTTP ${res.status}`);
  const body = await res.json();
  const text = body.candidates?.[0]?.content?.parts?.map((p) => p.text).join('') ?? '';
  if (!text) throw new Error('Gemini 응답에 텍스트 없음');
  return text;
}

async function callAnthropic(prompt) {
  const key = findEnvKey('ANTHROPIC_API_KEY', MODULE_DIR);
  if (!key) throw new Error('ANTHROPIC_API_KEY 없음');
  const model = process.env.ANTHROPIC_JUDGE_MODEL || 'claude-haiku-4-5-20251001';
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-api-key': key, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify({ model, max_tokens: 2048, messages: [{ role: 'user', content: prompt }] }),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`Anthropic HTTP ${res.status}`);
  const body = await res.json();
  const text = (body.content ?? []).map((c) => c.text ?? '').join('');
  if (!text) throw new Error('Anthropic 응답에 텍스트 없음');
  return text;
}

const CALLERS = { gemini: callGemini, anthropic: callAnthropic };

const round2 = (v) => Math.round(v * 100) / 100;

/**
 * 브리핑 인과 품질 판정 (advisory).
 * @param {string} briefingText
 * @param {Array} signals — 파생 신호 (present 인 것만 판정 대상)
 * @param {{ caller?: (prompt)=>Promise<string>, provider?: string }} opts — caller 는 테스트 주입점
 * @returns {{skipped:true, reason:string} | {provider, perSignal, averages, advisory}}
 */
export async function judgeBriefing(briefingText, signals, opts = {}) {
  const provider = opts.provider ?? (opts.caller ? 'injected' : resolveProvider());
  if (!provider) {
    return { skipped: true, reason: 'LLM 키 없음 (GEMINI_API_KEY/ANTHROPIC_API_KEY) 또는 BRIEFING_JUDGE_PROVIDER=off' };
  }
  const present = signals.filter((s) => s.present);
  if (present.length === 0) {
    return { skipped: true, reason: '판정할 PRESENT 신호 없음 (음성 브리핑은 규칙 채점으로 충분)' };
  }
  const caller = opts.caller ?? CALLERS[provider];
  if (!caller) return { skipped: true, reason: `알 수 없는 provider: ${provider}` };

  const prompt = buildJudgePrompt(briefingText, present);
  let raw;
  try {
    raw = await caller(prompt);
  } catch (e) {
    return { skipped: true, reason: `Judge 호출 실패 — ${safeErrorMessage(e)}` };
  }
  const perSignal = parseJudgeVerdict(raw, present.map((s) => s.id));

  const avg = (key) => round2(perSignal.reduce((acc, r) => acc + r[key], 0) / perSignal.length);
  const averages = { causality: avg('causality'), decision: avg('decision'), falsifiability: avg('falsifiability') };
  const values = Object.values(averages);
  const advisory = values.every((v) => v >= 1.5) ? '우수'
    : values.every((v) => v >= 1.0) ? '보통'
    : '보완 필요';

  return { provider, perSignal, averages, advisory };
}
