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

import { execFileSync, spawnSync } from 'node:child_process';
import { readFile, realpath, stat } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ALLOW = /harness-guard:\s*allow/i;
const ALLOWANCE = /harness-guard:\s*allow\s+reason="([^"]*)"\s+issue="([^"]*)"\s+owner="([^"]*)"\s+expires="([^"]*)"\s*$/i;
const ISSUE = /^(ISSUE-\d+|https:\/\/github\.com\/[^/\s]+\/[^/\s]+\/issues\/\d+)$/;
const OWNER = /^team-[a-z0-9]+(?:-[a-z0-9]+)*$/;

const POLICY_ROOT = /(?:^|\/)((?:hackathon|pwc|settlement-service|account-service|market-service)\/.*)$/;

function policyPath(filePath) {
  const normalized = String(filePath).replaceAll('\\', '/');
  return normalized.match(POLICY_ROOT)?.[1] ?? normalized;
}

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
  const comparablePath = policyPath(f);
  if (NO_COMMIT.test(comparablePath)) {
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
    if (!rule.when(comparablePath)) continue;
    lines.forEach((line, i) => {
      if (lineAllowances[i]) return;
      // ignore comment-only lines for code rules (keep SQL/DDL scanning)
      if (JAVA_KT.test(f) && /^\s*(\/\/|\*|\/\*)/.test(line)) return;
      if (rule.test(line)) violations.push({ file: f, line: i + 1, id: rule.id, msg: rule.msg });
    });
  }
  return { violations, allowances };
}

export async function readUtf8Strict(filePath) {
  const bytes = await readFile(filePath);
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

async function readStdinUtf8Strict() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(Buffer.from(chunk));
  return new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
}

async function nearestExistingAncestor(filePath) {
  let candidate = filePath;
  for (;;) {
    try { await stat(candidate); return candidate; } catch (error) {
      if (error?.code !== 'ENOENT' && error?.code !== 'ENOTDIR') throw error;
      const parent = dirname(candidate);
      if (parent === candidate) throw error;
      candidate = parent;
    }
  }
}

function isContained(root, candidate) {
  const remainder = relative(root, candidate);
  return remainder !== '' && !remainder.startsWith('..') && !isAbsolute(remainder);
}

export async function normalizeRepoPath(repoRoot, requestedPath) {
  if (typeof requestedPath !== 'string' || requestedPath.length === 0) throw new Error('missing file path');
  const root = await realpath(resolve(repoRoot));
  const requested = resolve(root, requestedPath);
  if (!isContained(root, requested)) throw new Error('path is outside repository or is repository root');
  const ancestor = await nearestExistingAncestor(requested);
  const actualAncestor = await realpath(ancestor);
  if (actualAncestor !== root && !isContained(root, actualAncestor)) throw new Error('path escapes repository through a link');
  return requested;
}

function replaceExactlyOnce(content, oldString, newString) {
  if (typeof oldString !== 'string' || oldString.length === 0 || typeof newString !== 'string') throw new Error('invalid edit');
  const first = content.indexOf(oldString);
  if (first < 0 || content.indexOf(oldString, first + oldString.length) >= 0) throw new Error('edit must match exactly once');
  return content.slice(0, first) + newString + content.slice(first + oldString.length);
}

export async function reconstructPendingContent(event, { repoRoot }) {
  if (!event || typeof event !== 'object' || !event.tool_input || typeof event.tool_input !== 'object') throw new Error('malformed hook event');
  const input = event.tool_input;
  if ('new_path' in input || 'destination' in input) throw new Error('rename is unsupported');
  const filePath = await normalizeRepoPath(repoRoot, input.file_path);
  const tool = String(event.tool_name ?? event.tool ?? '').split('.').at(-1);
  if (tool === 'Write') {
    if (typeof input.content !== 'string') throw new Error('Write content is required');
    return input.content;
  }
  if (tool === 'Edit') return replaceExactlyOnce(await readUtf8Strict(filePath), input.old_string, input.new_string);
  if (tool === 'MultiEdit') {
    if (!Array.isArray(input.edits) || input.edits.length === 0) throw new Error('MultiEdit edits are required');
    let content = await readUtf8Strict(filePath);
    for (const edit of input.edits) content = replaceExactlyOnce(content, edit?.old_string, edit?.new_string);
    return content;
  }
  throw new Error('unsupported hook tool');
}

export function discoverStagedFiles(repoRoot) {
  const output = execFileSync('git', ['diff', '--cached', '--name-only', '-z', '-M', '--diff-filter=ACMR'], { cwd: repoRoot });
  return output.toString('utf8').split('\0').filter(Boolean);
}

async function scanRepoFile(repoRoot, requestedPath) {
  const absolute = await normalizeRepoPath(repoRoot, requestedPath);
  return scanText(requestedPath, await readUtf8Strict(absolute)).violations;
}

function emitReport(violations, io = {}) {
  const stdout = io.stdout ?? ((message) => console.log(message));
  const stderr = io.stderr ?? ((message) => console.error(message));
  if (violations.length === 0) { stdout('harness guard: clean'); return 0; }
  stderr(`harness guard: ${violations.length} blocking violation(s)`);
  for (const violation of violations) stderr(`[${violation.id}] ${violation.file}${violation.line ? `:${violation.line}` : ''} ${violation.msg}`);
  return 1;
}

export async function runGuardCli(args, io = {}) {
  const repoRoot = io.repoRoot ?? process.cwd();
  const stderr = io.stderr ?? ((message) => console.error(message));
  const modes = ['--staged', '--list', '--files', '--hook', '--self-test'].filter((mode) => args.includes(mode));
  if (modes.length !== 1) { stderr('exactly one guard mode is required'); return 2; }
  const mode = modes[0];
  if (mode === '--self-test') {
    if (args.length !== 1) return 2;
    return spawnSync(process.execPath, ['--test', fileURLToPath(new URL('./test/guard.test.mjs', import.meta.url))], { cwd: repoRoot, stdio: 'inherit' }).status ?? 2;
  }
  if (mode === '--hook') {
    if (args.length !== 1) return 2;
    try {
      const event = JSON.parse(io.stdin ?? await readStdinUtf8Strict());
      const pending = await reconstructPendingContent(event, { repoRoot });
      return emitReport(scanText(event.tool_input.file_path, pending).violations, io) ? 2 : 0;
    } catch (error) { stderr(`guard hook rejected input: ${error.message}`); return 2; }
  }
  try {
    let files;
    if (mode === '--staged') {
      if (args.length !== 1) return 2;
      files = discoverStagedFiles(repoRoot);
    } else if (mode === '--list') {
      if (args.length !== 2) return 1;
      const listPath = await normalizeRepoPath(repoRoot, args[1]);
      files = (await readUtf8Strict(listPath)).split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
    } else {
      if (args[0] !== '--files' || args.length < 2) return 1;
      files = args.slice(1);
    }
    const violations = [];
    for (const file of files) violations.push(...await scanRepoFile(repoRoot, file));
    return emitReport(violations, io);
  } catch (error) { stderr(`guard input failed: ${error.message}`); return 1; }
}


if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  process.exit(await runGuardCli(process.argv.slice(2)));
}
