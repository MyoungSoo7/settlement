#!/usr/bin/env node
// Lemuel harness telemetry report — .claude/harness/logs/*.jsonl 집계 CLI.
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
import { RULES, scanText } from './guard.mjs';
import { LOG_DIR_SEGMENTS } from './telemetry.mjs';

// 가드 카나리아 — 규칙별 "반드시 차단돼야 하는" 최소 위반 픽스처. 차단 0회의 모호성
// (죽은 규칙 vs 완전 예방)을 분리한다: 카나리아 PASS = 규칙 살아있음 → 0회는 예방 성공.
export const GUARD_CANARIES = {
  'MONEY-PRIMITIVE': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
    line: 'double amount = 1.0;',
  },
  'MONEY-BIGDECIMAL-DOUBLE': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
    line: 'BigDecimal fee = new BigDecimal(0.1);',
  },
  'IMMUTABLE-HISTORY': {
    file: 'settlement-service/src/main/resources/db/migration/V0__canary.sql',
    line: 'UPDATE settlements SET status = 1;',
  },
  'MSA-BOUNDARY': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/application/service/X.java',
    line: 'import github.lms.lemuel.order.domain.Order;',
  },
  'ACCOUNT-CONSUME-ONLY': {
    file: 'account-service/src/main/java/github/lms/lemuel/account/application/service/X.java',
    line: 'kafkaTemplate.send(topic, payload);',
  },
  'MARKET-NO-VALUATION': {
    file: 'market-service/src/main/java/github/lms/lemuel/market/application/service/X.java',
    line: 'BigDecimal PER = price.divide(eps);',
  },
  'OO-DOMAIN-SETTER': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/X.java',
    line: 'public void setAmount(BigDecimal amount) {',
  },
  'OO-DOMAIN-MUTABLE-LOMBOK': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/X.java',
    line: '@Setter',
  },
  'OO-DOMAIN-GENERIC-IAE': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/X.java',
    line: 'throw new IllegalArgumentException("bad");',
  },
};

export function canaryResults({ rules = RULES, canaries = GUARD_CANARIES } = {}) {
  return rules.map((rule) => {
    const fixture = canaries[rule.id];
    if (!fixture) return { id: rule.id, status: 'undefined' };
    const { violations } = scanText(fixture.file, `${fixture.line}\n`);
    return { id: rule.id, status: violations.some((violation) => violation.id === rule.id) ? 'pass' : 'fail' };
  });
}

export function canaryReport(results = canaryResults()) {
  const lines = [`== 가드 카나리아 (규칙 생존 검사 — 차단 0회 = 완전 예방인지 판별) ==`];
  for (const { id, status } of results) {
    const mark = status === 'pass' ? 'PASS' : status === 'fail' ? 'FAIL — 규칙이 죽었다(위반 픽스처 미차단)' : 'UNDEFINED — 카나리아 픽스처 미정의';
    lines.push(`  ${mark.padEnd(4)}  ${id}`);
  }
  return lines.join('\n');
}

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
  const consumed = suggestions.filter((record) => used.has(record.skill)).length;
  const ignored = countBy(suggestions.filter((record) => !used.has(record.skill)), 'skill');
  lines.push(`== 라우터 제안 (${suggestions.length}건) — 제안됐지만 미로드 스킬 ==`);
  if (suggestions.length > 0) {
    lines.push(`  순응률(제안→로드) ${Math.round((consumed / suggestions.length) * 100)}% (${consumed}/${suggestions.length}) — 목표 ≥80%`);
  }
  for (const [skill, count] of ignored) lines.push(`  ${String(count).padStart(4)}  ${skill}`);
  if (ignored.length === 0) lines.push('  (없음 — 제안이 모두 소비됨)');
  return lines.join('\n');
}

// SessionStart 훅용 압축 요약 — 사람이 리포트를 돌리지 않아도 세션마다 관측이 도달하게 한다
// (닫힌 피드백 루프). 알릴 것이 없으면 null(침묵) — 스팸 방지.
export function hookSummary({ hits, usage, suggestions, canaries, days = 14, now = new Date() }) {
  const lines = [];
  const since = new Date(now.getTime() - days * 86_400_000).toISOString();
  const recent = hits.filter((hit) => typeof hit.ts === 'string' && hit.ts >= since);
  if (recent.length > 0) {
    const counts = new Map();
    for (const hit of recent) counts.set(hit.id, (counts.get(hit.id) ?? 0) + 1);
    const [topId, topCount] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0];
    lines.push(`가드 차단 최근 ${days}일 ${recent.length}건(최다 ${topId} ${topCount}회)`);
  }
  if (suggestions.length > 0) {
    const used = new Set(usage.map((record) => record.skill));
    const consumed = suggestions.filter((record) => used.has(record.skill)).length;
    lines.push(`라우터 순응률(제안→로드) ${Math.round((consumed / suggestions.length) * 100)}% (${consumed}/${suggestions.length}, 목표 ≥80%)`);
  }
  const failed = canaries.filter(({ status }) => status !== 'pass').map(({ id }) => id);
  if (failed.length > 0) lines.push(`⚠ 카나리아 FAIL(규칙 사망 의심): ${failed.join(', ')}`);
  if (lines.length === 0) return null;
  return `하네스 텔레메트리 요약: ${lines.join(' · ')} — 상세: node scripts/harness/telemetry-report.mjs`;
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
  if (args.includes('--hook')) {
    // 관측은 세션을 절대 깨뜨리지 않는다 — 어떤 실패도 침묵 + exit 0.
    try {
      const message = hookSummary({
        hits, usage, suggestions,
        canaries: canaryResults(),
        days: Number.isFinite(days) && days > 0 ? days : 14,
        now: io.now ?? new Date(),
      });
      if (message) stdout(JSON.stringify({ hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext: message } }));
    } catch { /* silent */ }
    return 0;
  }
  if (hits.length + usage.length + suggestions.length === 0) {
    stdout(`harness telemetry: no data yet (${logDir})`);
    stdout(canaryReport());
    return 0;
  }
  stdout(summarize({ hits, usage, suggestions, days: Number.isFinite(days) && days > 0 ? days : 14, now: io.now ?? new Date() }));
  stdout(canaryReport());
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runReportCli(process.argv.slice(2));
}
