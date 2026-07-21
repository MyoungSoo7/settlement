#!/usr/bin/env node
// Lemuel skill router — Claude Code PreToolUse hook (matcher: Write|Edit|MultiEdit|Skill).
//
// CLAUDE.md / HARNESS.md 라우팅 표("X-service 를 만지면 해당 *-rules 스킬 로드")는 지금까지
// LLM 이 기억해서 지키는 문서 규율이었다. 이 훅은 그 권장을 기계화한다: 편집 대상 경로를 보고
// 해당 규칙 스킬 로드를 additionalContext 리마인더로 주입한다(세션당 스킬별 1회 — 스팸 방지).
// guard.mjs 가 "금지의 기계화"라면 이 훅은 "권장의 기계화"다.
//
// 부수 기능: Skill 도구 호출을 텔레메트리로 적재해(skill-usage.jsonl) 스킬 사용률을 측정한다 —
// 제안됐지만 로드되지 않는 스킬 / 한 번도 안 불리는 스킬을 telemetry-report.mjs 가 드러낸다.
//
// Invariant: 이 훅은 절대 도구 호출을 차단하지 않는다 — 어떤 실패도 exit 0.

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { appendJsonl } from './telemetry.mjs';

const SOURCE = /\.(java|kt|sql)$/i;

// [pattern, skills, anyFile?] — anyFile 은 java/kt/sql 외 파일(계약 JSON 등)에도 적용.
export const ROUTES = [
  [/settlement-service\/.*ledger/i, ['ledger-invariants', 'settlement-domain-rules']],
  [/settlement-service\//, ['settlement-domain-rules']],
  [/order-service\//, ['order-commerce-rules']],
  [/loan-service\//, ['loan-domain-rules']],
  [/investment-service\//, ['investment-domain-rules']],
  [/account-service\//, ['account-domain-rules', 'ledger-invariants']],
  [/market-service\//, ['market-quotes-rules']],
  [/financial-statements-service\//, ['financial-data-rules']],
  [/economics-service\//, ['economics-data-rules']],
  [/company-service\//, ['company-news-rules']],
  [/operation-service\//, ['operation-signal-rules']],
  [/ai-service\//, ['ai-chat-rules']],
  [/common-data-service\//, ['commondata-connector-rules']],
  [/(\/outbox\/|adapter\/in\/kafka\/|adapter\/out\/event\/)/i, ['idempotency-and-events']],
  [/settlement-service\/.*(readmodel|projection)/i, ['projection-view-ops']],
  [/contracts\/events\//, ['event-contract-change'], true],
  // 절차 규율(플러그인 독립): 세션 첫 소스 편집에 1회 — 테스트 우선 절차 리마인더. 마지막 순위(cap 3에서 도메인 규칙 우선).
  [/\/src\/(main|test)\//, ['tdd-discipline']],
];

export function routeSkills(filePath) {
  const normalized = String(filePath ?? '').replaceAll('\\', '/');
  const isSource = SOURCE.test(normalized);
  const skills = [];
  for (const [pattern, names, anyFile] of ROUTES) {
    if (!anyFile && !isSource) continue;
    if (!pattern.test(normalized)) continue;
    for (const name of names) if (!skills.includes(name)) skills.push(name);
  }
  return skills.slice(0, 3);
}

function stateFilePath(repoRoot, sessionId) {
  const id = String(sessionId ?? '').replace(/[^a-zA-Z0-9-]/g, '').slice(0, 64) || 'global';
  return resolve(repoRoot, '.claude', 'harness', 'state', `skill-router-${id}.json`);
}

async function readSuggested(statePath) {
  try {
    const parsed = JSON.parse(await readFile(statePath, 'utf8'));
    return Array.isArray(parsed?.suggested) ? parsed.suggested.filter((s) => typeof s === 'string') : [];
  } catch {
    return [];
  }
}

async function writeSuggested(statePath, suggested) {
  try {
    await mkdir(dirname(statePath), { recursive: true });
    await writeFile(statePath, JSON.stringify({ suggested }), 'utf8');
  } catch { /* state loss only degrades dedupe, never the hook */ }
}

async function readStdinUtf8() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks).toString('utf8');
}

// Returns the hook JSON output string to print, or null when silent. Never throws.
export async function decideHookOutput(event, { repoRoot, now = new Date() } = {}) {
  try {
    const tool = String(event?.tool_name ?? event?.tool ?? '').split('.').at(-1);
    const ts = now.toISOString();
    if (tool === 'Skill') {
      await appendJsonl(repoRoot, 'skill-usage.jsonl', {
        ts,
        skill: typeof event.tool_input?.skill === 'string' ? event.tool_input.skill : null,
        session: typeof event.session_id === 'string' ? event.session_id.slice(0, 64) : null,
      });
      return null;
    }
    const skills = routeSkills(event?.tool_input?.file_path);
    if (skills.length === 0) return null;
    const statePath = stateFilePath(repoRoot, event?.session_id);
    const seen = await readSuggested(statePath);
    const fresh = skills.filter((skill) => !seen.includes(skill));
    if (fresh.length === 0) return null;
    await writeSuggested(statePath, [...seen, ...fresh]);
    await appendJsonl(repoRoot, 'skill-suggestions.jsonl', fresh.map((skill) => ({ ts, skill, file: event.tool_input.file_path })));
    const context = `스킬 라우터: 이 파일은 ${fresh.map((skill) => `'${skill}'`).join(', ')} 스킬의 강제 규칙 대상입니다. 이번 세션에서 아직 로드하지 않았다면 Skill 도구로 로드한 뒤 작업하세요 (HARNESS.md 라우팅 맵).`;
    return JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: context } });
  } catch {
    return null;
  }
}

export async function runRouterCli(args, io = {}) {
  if (args[0] !== '--hook' || args.length !== 1) {
    (io.stderr ?? ((m) => console.error(m)))('usage: skill-router.mjs --hook');
    return 0; // advisory hook: never block, even on misuse
  }
  try {
    const event = JSON.parse(io.stdin ?? await readStdinUtf8());
    const output = await decideHookOutput(event, { repoRoot: io.repoRoot ?? process.cwd(), now: io.now });
    if (output) (io.stdout ?? ((m) => console.log(m)))(output);
  } catch { /* malformed input → stay silent, stay green */ }
  return 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  process.exit(await runRouterCli(process.argv.slice(2)));
}
