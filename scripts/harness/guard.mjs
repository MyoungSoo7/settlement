#!/usr/bin/env node
// Lemuel harness guard — PLUGIN-INDEPENDENT, repo-tracked core invariant enforcement.
//
// Why this exists: the settlement-copilot / invest-copilot plugin guards live under
// gitignore-prone `hackathon/` and are not reproducible on a fresh clone. This script
// re-implements the *non-negotiable* money/architecture invariants with zero external
// dependency so the guard survives plugin relocation and works in CI. (See HARNESS.md
// "하드스톱" — this is the machine-enforced subset of that section.)
//
// Modes:
//   node scripts/harness/guard.mjs --staged        # scan git staged files (pre-commit / CI)
//   node scripts/harness/guard.mjs --files a.java b.sql
//   node scripts/harness/guard.mjs --hook          # Claude Code PreToolUse (reads JSON on stdin)
//
// Exit 0 = clean, Exit 1 = blocking violation(s). Exceptions require structured metadata.

import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ALLOW = /harness-guard:\s*allow/i;
const ALLOWANCE = /harness-guard:\s*allow\s+reason="([^"]*)"\s+issue="([^"]*)"\s+owner="([^"]*)"\s+expires="([^"]*)"\s*$/i;
const ISSUE = /^(ISSUE-\d+|https:\/\/github\.com\/[^/\s]+\/[^/\s]+\/issues\/\d+)$/;
const OWNER = /^team-[a-z0-9]+(?:-[a-z0-9]+)*$/;

// Money-scope = files where BigDecimal / immutable-history rules are non-negotiable.
const MONEY_SCOPE = /(settlement|ledger|payout|chargeback|loan|payment|investment|account|pgreconciliation|recon)/i;
const JAVA_KT = /\.(java|kt)$/i;
const SQL = /\.sql$/i;
// Money math lives in domain/application; adapters legitimately use double for
// Micrometer gauges, pagination, mock probabilities, JDBC — scope the primitive
// rule to core layers and exclude test sources to keep the gate false-positive free.
const isCore = (f) => /\/(domain|application)\//.test(f);
const isProd = (f) => !/\/src\/test\//.test(f);

// Paths that must never be committed (portfolio hygiene — see MEMORY feedback).
const NO_COMMIT = /^(hackathon|pwc)\//;

const RULES = [
  {
    id: 'MONEY-PRIMITIVE',
    // double/float primitive or parse in money-scope core java/kt → must use BigDecimal.
    when: (f) => JAVA_KT.test(f) && MONEY_SCOPE.test(f) && isCore(f) && isProd(f),
    test: (line) =>
      /\b(double|float)\s+\w+\s*[=;)]/.test(line) ||
      /\b(Double|Float)\.parse(Double|Float)\s*\(/.test(line) ||
      /\(\s*(double|float)\s*\)/.test(line), // cast to double/float
    msg: '금액 스코프에서 double/float 사용 금지 → BigDecimal 사용 (money-safety)',
  },
  {
    id: 'IMMUTABLE-HISTORY',
    // UPDATE/DELETE on append-only ledgers (SQL always; java prod only — test fixtures may seed).
    when: (f) => SQL.test(f) || (JAVA_KT.test(f) && MONEY_SCOPE.test(f) && isProd(f)),
    test: (line) =>
      /\b(UPDATE|DELETE\s+FROM)\s+(settlements|ledger_entries|payouts|account_entries)\b/i.test(line),
    msg: '정산/원장/지급/GL 레코드 UPDATE·DELETE 금지 → 조정/역분개 레코드 추가 (immutable history, ADR 0004/0007)',
  },
  {
    id: 'MSA-BOUNDARY',
    // settlement-service importing order-service domain code.
    when: (f) => JAVA_KT.test(f) && /settlement-service\//.test(f),
    test: (line) => /^\s*import\s+github\.lms\.lemuel\.(order|user|cart|product|coupon|shipping)\b/.test(line),
    msg: 'settlement-service 가 order 컨텍스트 import 금지 → Kafka 프로젝션/내부 대사 API 만 (ADR 0020)',
  },
  {
    id: 'ACCOUNT-CONSUME-ONLY',
    // account-service must not publish events (consume-only ledger).
    when: (f) => JAVA_KT.test(f) && /account-service\//.test(f) && isProd(f),
    test: (line) => /\bkafkaTemplate\.\s*send\s*\(/.test(line) || /SaveOutboxEventPort/.test(line),
    msg: 'account-service 는 소비 전용 → 이벤트 발행 금지 (Outbox 머시너리 배제)',
  },
  {
    id: 'MARKET-NO-VALUATION',
    // market-service must not compute PER/PBR (serves quotes only).
    when: (f) => JAVA_KT.test(f) && /market-service\//.test(f) && isProd(f),
    test: (line) => /\b(PER|PBR|priceEarnings|priceToBook)\b/.test(line) && /=/.test(line),
    msg: 'market-service 는 PER/PBR 계산 금지 → 시세·시총만 서빙 (밸류에이션 조인은 소비측)',
  },
];

export function parseAllowance(line, { now = new Date() } = {}) {
  const match = String(line).match(ALLOWANCE);
  if (!match) return null;

  const [, reason, issue, owner, expires] = match;
  const dateMatch = expires.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!reason.trim() || !ISSUE.test(issue) || !OWNER.test(owner) || !dateMatch) return null;

  const [year, month, day] = dateMatch.slice(1).map(Number);
  const expiry = new Date(Date.UTC(year, month - 1, day));
  if (
    expiry.getUTCFullYear() !== year
    || expiry.getUTCMonth() !== month - 1
    || expiry.getUTCDate() !== day
  ) return null;

  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  if (expiry.getTime() <= today) return null;
  return { reason, issue, owner, expires };
}

export function scanText(f, content, { now = new Date() } = {}) {
  const violations = [];
  const allowances = [];
  if (NO_COMMIT.test(f)) {
    violations.push({ file: f, line: 0, id: 'NO-COMMIT', msg: `${f} — hackathon/·pwc/ 경로는 커밋 금지 (add -f 우회 금지)` });
    return { violations, allowances };
  }
  const lines = String(content).split(/\r?\n/);
  const lineAllowances = lines.map((line, i) => {
    if (!ALLOW.test(line)) return null;
    const allowance = parseAllowance(line, { now });
    if (!allowance) {
      violations.push({ file: f, line: i + 1, id: 'INVALID-ALLOWANCE', msg: 'harness-guard 예외는 유효한 reason, issue, owner, 미래 expires가 필요함' });
      return null;
    }
    allowances.push({ file: f, line: i + 1, ...allowance });
    return allowance;
  });
  for (const rule of RULES) {
    if (!rule.when(f)) continue;
    lines.forEach((line, i) => {
      if (lineAllowances[i]) return;
      // ignore comment-only lines for code rules (keep SQL/DDL scanning)
      if (JAVA_KT.test(f) && /^\s*(\/\/|\*|\/\*)/.test(line)) return;
      if (rule.test(line)) violations.push({ file: f, line: i + 1, id: rule.id, msg: rule.msg });
    });
  }
  return { violations, allowances };
}

function scanFile(f) {
  if (NO_COMMIT.test(f)) return scanText(f, '').violations;
  if (!existsSync(f)) return [];
  try { return scanText(f, readFileSync(f, 'utf8')).violations; } catch { return []; }
}

function stagedFiles() {
  try {
    return execSync('git diff --cached --name-only --diff-filter=ACM', { encoding: 'utf8' })
      .split('\n').map((s) => s.trim()).filter(Boolean);
  } catch { return []; }
}

function report(violations) {
  if (violations.length === 0) {
    console.log('✅ harness guard: 위반 없음');
    return 0;
  }
  console.error('\n🚫 harness guard: 차단된 위반 ' + violations.length + '건\n');
  for (const v of violations) {
    console.error(`  [${v.id}] ${v.file}${v.line ? ':' + v.line : ''}`);
    console.error(`      → ${v.msg}`);
  }
  console.error('\n의도된 예외면 해당 줄에 감사 가능한 구조화 예외를 추가하라.\n');
  return 1;
}

function main(argv) {
  let files = [];
  if (argv.includes('--staged')) {
    files = stagedFiles();
  } else if (argv.includes('--list')) {
    // Read newline-separated paths from a file (CI diff — avoids argv length limits).
    const lf = argv[argv.indexOf('--list') + 1];
    try { files = readFileSync(lf, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean); } catch { files = []; }
  } else if (argv.includes('--files')) {
    files = argv.slice(argv.indexOf('--files') + 1);
  } else if (argv.includes('--hook')) {
  // Claude Code PreToolUse: {tool_input:{file_path, content|new_string|edits}} on stdin.
  // Scan the PENDING content (before it lands on disk), not the current file.
  let raw = '';
  try { raw = readFileSync(0, 'utf8'); } catch { /* no stdin */ }
  try {
    const j = JSON.parse(raw || '{}');
    const ti = j?.tool_input ?? {};
    const fp = ti.file_path;
    if (fp) {
      const pending = ti.content
        ?? ti.new_string
        ?? (Array.isArray(ti.edits) ? ti.edits.map((e) => e.new_string || '').join('\n') : undefined);
      const all = pending !== undefined ? scanText(fp, pending).violations : scanFile(fp);
      // PreToolUse blocks on exit code 2 (stderr fed back to the model); 0 = allow.
      return report(all) ? 2 : 0;
    }
  } catch { /* ignore malformed input — do not block */ }
    return 0;
  }

  const all = files.flatMap(scanFile);
  return report(all);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  process.exit(main(process.argv.slice(2)));
}
