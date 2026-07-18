#!/usr/bin/env node
// Lemuel harness telemetry report — .omc/logs/*.jsonl 집계 CLI.
//
//   node scripts/harness/telemetry-report.mjs [--root <repo>] [--days N]
//
// 보여주는 것:
//   1. 가드 차단 통계 — 규칙별 발화 횟수(0회 규칙 = 죽은 규칙 후보), 모드별, 최근 일별 추이
//   2. 스킬 사용률 — Skill 도구로 실제 로드된 스킬 횟수
//   3. 라우터 제안 대비 미로드 스킬 — 제안은 됐지만 한 번도 로드 안 된 스킬(권장 무시 신호)
//
// 로그가 없으면 "no data" 로 정상 종료한다(설치 직후 상태). 항상 exit 0 — 리포트는 게이트가 아니다.

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { RULES } from './guard.mjs';
import { LOG_DIR_SEGMENTS } from './telemetry.mjs';

export function readJsonl(path) {
  if (!existsSync(path)) return [];
  return readFileSync(path, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean)
    .flatMap((line) => {
      try { return [JSON.parse(line)]; } catch { return []; }
    });
}

function countBy(records, key) {
  const counts = new Map();
  for (const record of records) {
    const value = record[key] ?? '(unknown)';
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

export function summarize({ hits, usage, suggestions, days = 14, now = new Date() }) {
  const lines = [];
  const since = new Date(now.getTime() - days * 86_400_000).toISOString();
  const recent = hits.filter((hit) => typeof hit.ts === 'string' && hit.ts >= since);

  lines.push(`== 가드 차단 (전체 ${hits.length}건, 최근 ${days}일 ${recent.length}건) ==`);
  const byRule = new Map(countBy(hits, 'id'));
  for (const rule of RULES) lines.push(`  ${String(byRule.get(rule.id) ?? 0).padStart(4)}  ${rule.id}${byRule.get(rule.id) ? '' : '   (0회 — 죽은 규칙 후보 또는 완전 예방)'}`);
  for (const [id, count] of byRule) if (!RULES.some((rule) => rule.id === id)) lines.push(`  ${String(count).padStart(4)}  ${id} (규칙 로스터 외 — 과거 규칙?)`);
  for (const [mode, count] of countBy(hits, 'mode')) lines.push(`  mode ${mode}: ${count}`);
  for (const [day, count] of countBy(recent.map((hit) => ({ day: hit.ts.slice(0, 10) })), 'day').sort()) lines.push(`  ${day}: ${count}`);

  lines.push(`== 스킬 사용 (${usage.length}회 로드) ==`);
  for (const [skill, count] of countBy(usage, 'skill')) lines.push(`  ${String(count).padStart(4)}  ${skill}`);

  const used = new Set(usage.map((record) => record.skill));
  const ignored = countBy(suggestions.filter((record) => !used.has(record.skill)), 'skill');
  lines.push(`== 라우터 제안 (${suggestions.length}건) — 제안됐지만 미로드 스킬 ==`);
  for (const [skill, count] of ignored) lines.push(`  ${String(count).padStart(4)}  ${skill}`);
  if (ignored.length === 0) lines.push('  (없음 — 제안이 모두 소비됨)');
  return lines.join('\n');
}

export async function runReportCli(args, io = {}) {
  const stdout = io.stdout ?? ((text) => console.log(text));
  const value = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index === -1 ? fallback : args[index + 1];
  };
  const root = resolve(value('--root', io.repoRoot ?? process.cwd()));
  const days = Number(value('--days', '14'));
  const logDir = resolve(root, ...LOG_DIR_SEGMENTS);
  const hits = readJsonl(resolve(logDir, 'guard-hits.jsonl'));
  const usage = readJsonl(resolve(logDir, 'skill-usage.jsonl'));
  const suggestions = readJsonl(resolve(logDir, 'skill-suggestions.jsonl'));
  if (hits.length + usage.length + suggestions.length === 0) {
    stdout(`harness telemetry: no data yet (${logDir})`);
    return 0;
  }
  stdout(summarize({ hits, usage, suggestions, days: Number.isFinite(days) && days > 0 ? days : 14, now: io.now ?? new Date() }));
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runReportCli(process.argv.slice(2));
}
