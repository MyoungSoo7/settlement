#!/usr/bin/env node
// Lemuel harness guard — PLUGIN-INDEPENDENT, repo-tracked core invariant enforcement.
//
// Why this exists: the settlement-copilot / invest-copilot plugin guards live outside the build
// graph (service `src/main/resources/` — jar-excluded) and are not
// wired into CI on a fresh clone. This
// script re-implements the *non-negotiable* money/architecture invariants with zero external
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
import { readFileSync } from 'node:fs';
import { readFile, realpath, stat } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { appendJsonl, logGuardHits } from './telemetry.mjs';

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
const MONEY_SCOPE = /(settlement|ledger|payout|chargeback|loan|payment|investment|account|insurance|pgreconciliation|recon)/i;
const JAVA_KT = /\.(java|kt)$/i;
const SQL = /\.sql$/i;
// Money math lives in domain/application; adapters legitimately use double for
// Micrometer gauges, pagination, mock probabilities, JDBC — scope the primitive
// rule to core layers and exclude test sources to keep the gate false-positive free.
const isCore = (f) => /\/(domain|application)\//.test(f);
const isProd = (f) => !/\/src\/test\//.test(f);

// OO invariants — 2026-07-14 OO 캠페인(패널 중앙값 9.5+)을 만든 구조의 회귀 방지.
// 도메인 프로덕션 소스만 대상. common/audit(감사 인프라 DTO)은 3회 패널 모두 대상 밖 판정.
const isDomainMain = (f) => JAVA_KT.test(f) && /\/src\/main\/java\/.+\/domain\//.test(f) && !/common\/audit\//.test(f);
// generic 예외 금지는 캠페인이 청정화를 완료한 5개 금융 서비스에 한정(위성 서비스는 oo-score 스킬로 채점).
const CAMPAIGN_SERVICES = /(settlement|order|loan|investment|account|insurance)-service\//;

// settlement-service 가 import 해도 되는 자기 바운디드 컨텍스트(+shared-common `common`).
// 이 집합의 여집합(order 컨텍스트: order/user/cart/product/coupon/shipping/payment/review/game/category/menu/rbac…)은
// MSA 경계 위반. enum denylist 는 신규 order 도메인 누락에 취약하므로 allowlist 여집합으로 강제한다(감사 MED-2).
const SETTLEMENT_OWN_PACKAGES = new Set([
  'settlement', 'payout', 'ledger', 'chargeback', 'pgreconciliation',
  'recon', 'recovery', 'report', 'tax', 'idempotency', 'integrity', 'closing',
  'common',
]);

export const RULES = [
  {
    id: 'MONEY-PRIMITIVE',
    // double/float primitive or parse in money-scope core java/kt → must use BigDecimal.
    // 선언·파라미터·반환타입·배열·캐스트·var 더블 리터럴 추론까지 커버(우회면 봉쇄).
    when: (f) => JAVA_KT.test(f) && MONEY_SCOPE.test(f) && isCore(f) && isProd(f),
    test: (line) =>
      /\b(double|float)\s+\w+\s*[=;,)(]/.test(line) ||
      /\b(double|float)\s*\[\]/.test(line) ||
      /\b(Double|Float)\.parse(Double|Float)\s*\(/.test(line) ||
      /\(\s*(double|float)\s*\)/.test(line) || // cast to double/float
      /\bvar\s+\w+\s*=\s*-?\d[\d_]*\.\d/.test(line), // var fee = 0.035;
    // 개행 분할 우회(Double\n.parseDouble) 차단 — 파일 단위 멀티라인 스캔.
    fileTest: /\b(?:Double|Float)\s*\.\s*parse(?:Double|Float)\s*\(/g,
    msg: '금액 스코프에서 double/float 사용 금지 → BigDecimal 사용 (money-safety)',
  },
  {
    id: 'MONEY-BIGDECIMAL-DOUBLE',
    // new BigDecimal(0.1) 은 이진 부동소수 정밀도 손실을 그대로 흡수한다 → 문자열 생성자만.
    when: (f) => JAVA_KT.test(f) && MONEY_SCOPE.test(f) && isCore(f) && isProd(f),
    fileTest: /new\s+BigDecimal\s*\(\s*-?\d[\d_]*\.\d/g,
    msg: 'new BigDecimal(더블 리터럴) 금지 → new BigDecimal("0.1") 문자열 생성자 (money-safety, 정밀도 손실)',
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
    // settlement-service 는 자기 컨텍스트 + shared-common 외의 github.lms.lemuel.* 를 import 하지 않는다.
    // order 도메인 전부(payment·review·game·category·menu·rbac 등 신규 포함) 차단 — allowlist 여집합이라
    // denylist 나열 누락 함정이 없다(감사 MED-2). settlement 자체 `recon`(OrderReconClient)은 HTTP 대사라 허용.
    when: (f) => JAVA_KT.test(f) && /settlement-service\//.test(f),
    test: (line) => {
      // `import static github.lms.lemuel.order...` 도 잡는다 — static 키워드가 사이에 껴도 우회 못 하게(#7).
      const m = /^\s*import\s+(?:static\s+)?github\.lms\.lemuel\.([a-z0-9_]+)\b/.exec(line);
      return m != null && !SETTLEMENT_OWN_PACKAGES.has(m[1]);
    },
    msg: 'settlement-service 가 타 컨텍스트(order 등) import 금지 → Kafka 프로젝션/내부 대사 API 만 (ADR 0020)',
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
  {
    id: 'OO-DOMAIN-SETTER',
    // 도메인 public setter → 상태머신·불변식 우회 경로. 재구성은 rehydrate/팩토리로.
    when: isDomainMain,
    test: (line) => /public\s+void\s+set[A-Z]\w*\s*\(/.test(line),
    msg: '도메인 public setter 금지 → rehydrate/생성 팩토리 + 의미 있는 도메인 메서드 (OO 게이트, oo-score 스킬)',
  },
  {
    id: 'OO-DOMAIN-MUTABLE-LOMBOK',
    // @Setter/@Data 는 컴파일 타임에 setter 를 생성해 grep 기반 봉인을 우회한다. @Getter 는 허용.
    when: isDomainMain,
    test: (line) => /@(Setter|Data)\b/.test(line),
    msg: '도메인 @Setter/@Data 금지 — Lombok 생성 setter 는 캡슐화 우회 (@Getter 는 허용)',
  },
  {
    id: 'OO-DOMAIN-GENERIC-IAE',
    // 도메인 규칙 위반은 타입 도메인 예외(InvariantViolation/InvalidState 계열)로. write-once
    // 인프라 가드는 IllegalStateException 을 쓰므로 이 규칙과 충돌하지 않는다.
    when: (f) => isDomainMain(f) && CAMPAIGN_SERVICES.test(f),
    test: (line) => /throw\s+new\s+IllegalArgumentException\s*\(/.test(line),
    msg: '금융 5서비스 도메인에서 generic IllegalArgumentException throw 금지 → 타입 도메인 예외 (OO 게이트)',
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
      if (rule.test && rule.test(line)) violations.push({ file: f, line: i + 1, id: rule.id, msg: rule.msg });
    });
    if (!rule.fileTest) continue;
    // 파일 단위 멀티라인 스캔 — 라인 정규식을 개행 분할로 우회하는 표기를 잡는다.
    // 매치 시작 라인 기준으로 allowance·주석 판정(라인 스캔과 동일 규약), (id, line) 중복은 병합.
    const pattern = new RegExp(rule.fileTest.source, rule.fileTest.flags.includes('g') ? rule.fileTest.flags : `${rule.fileTest.flags}g`);
    for (const match of String(content).matchAll(pattern)) {
      const lineNumber = String(content).slice(0, match.index).split(/\r?\n/).length;
      if (lineAllowances[lineNumber - 1]) continue;
      if (JAVA_KT.test(f) && /^\s*(\/\/|\*|\/\*)/.test(lines[lineNumber - 1] ?? '')) continue;
      if (violations.some((violation) => violation.id === rule.id && violation.line === lineNumber && violation.file === f)) continue;
      violations.push({ file: f, line: lineNumber, id: rule.id, msg: rule.msg });
    }
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

// DoD 넛지(비차단) — 돈 경로(core/prod) 변경이 테스트 변경 없이 스테이지되면 stderr 리마인더.
// 차단하지 않는다: TDD 는 판단 영역(리팩터·문서화 커밋 등 정당한 예외 존재) — 게이트는 JaCoCo 가 정답.
export function dodNudgeMessage(files) {
  const normalized = files.map(policyPath);
  const money = normalized.filter((f) => JAVA_KT.test(f) && MONEY_SCOPE.test(f) && isCore(f) && isProd(f));
  if (money.length === 0) return null;
  if (normalized.some((f) => JAVA_KT.test(f) && /\/src\/test\//.test(f))) return null;
  return `DoD 넛지(비차단): 돈 경로 프로덕션 변경 ${money.length}건이 테스트 변경 없이 스테이지됨 — tdd-discipline·verify-before-done 절차와 게이트(:module:test + jacoco LINE 90%) 통과를 확인하고 커밋하세요.`;
}

// 하네스 자체를 이루는 경로 — 여기가 지워지면 가드·스킬·규율이 통째로 사라진다.
// 실제로 PR #210 에 섞인 삭제 커밋 3개가 .claude(81)·.codex(41)·docs/harness(148) 를 날렸고,
// 기존 스테이징 스캔이 --diff-filter=ACMR 로 삭제를 아예 보지 않아 그대로 통과했다.
//
// docs/harness 는 목록에 넣지 않는다 — 그 사고에 함께 휩쓸렸을 뿐 하네스 기계장치가 아니라
// 해커톤 제출물 보관함이고, 공개 저장소 위생상 의도적으로 비우는 대상이다(CLAUDE.md 배치 기준:
// 소유 서비스가 없는 제출물은 저장소에 두지 않는다). 보호 대상은 실행되는 하네스로 한정한다.
const PROTECTED_DELETE_ROOTS = ['.claude/', '.codex/', 'scripts/harness/'];

// 위 루트 안이지만 재생성 가능한 세션 상태 — 삭제를 막을 이유가 없다(정리 작업을 방해하지 않는다).
const PROTECTED_DELETE_EXEMPT = [
  '.claude/scratch/', '.claude/agent-memory/', '.claude/worktrees/', '.claude/harness/',
];

/**
 * 스테이징된 삭제 중 하네스 보호 경로에 해당하는 것을 위반으로 보고한다.
 *
 * <p>임계치를 두지 않는다 — "대량이면 막는다"는 규칙은 한 파일씩 여러 커밋으로 나누면 그대로
 * 뚫린다. 보호 경로의 삭제는 항상 의도적이어야 하므로 1건도 막고, 진짜 지울 때는
 * {@code HARNESS_ALLOW_DELETE=1} 로 명시적으로 opt-in 한다(그 사실이 실행 기록에 남는다).
 */
export function checkProtectedDeletions(deletedFiles, options = {}) {
  const allowDelete = options.allowDelete ?? process.env.HARNESS_ALLOW_DELETE === '1';
  if (allowDelete) return [];
  const violations = [];
  for (const file of deletedFiles ?? []) {
    const path = policyPath(file);
    if (PROTECTED_DELETE_EXEMPT.some((prefix) => path.startsWith(prefix))) continue;
    if (!PROTECTED_DELETE_ROOTS.some((prefix) => path.startsWith(prefix))) continue;
    violations.push({
      file,
      id: 'HARNESS-DELETE',
      msg: '하네스 경로 삭제 금지 — 스킬·가드·규율이 사라진다. 의도한 삭제면 HARNESS_ALLOW_DELETE=1 로 실행',
    });
  }
  return violations;
}

// ── KAFKA-DLQ: 컨슈머를 가진 서비스는 반드시 DLT 배선이 닿아야 한다 ──────────────────
//
// 왜 라인 규칙이 아니라 저장소 규칙인가: 이 위반은 "잘못 쓴 줄"이 아니라 "없는 파일"이다.
// 배선이 서비스마다 ~180줄씩 복붙되던 동안 card·insurance·operation 은 배선 자체가 누락됐고,
// Spring Kafka 기본 핸들러 FixedBackOff(0, 9) 로 떨어져 재시도 소진 메시지를 조용히 skip 했다
// (= 사실상 유실). 어떤 파일도 "틀리지" 않았기 때문에 라인 스캔으로는 영원히 안 잡힌다.
//
// 판정: 모듈 안에 @KafkaListener 가 있으면 공용 배선(KafkaConsumerErrorHandlingConfig)이
// 닿아야 한다. 닿는 경로는 둘 중 하나다.
//   (a) 루트 github.lms.lemuel 스캔 — @SpringBootApplication 이 루트 패키지에 있고 scanBasePackages 로
//       좁히지 않은 경우 (대부분의 서비스)
//   (b) 명시 @Import(KafkaConsumerErrorHandlingConfig.class) — 제한 스캔 서비스(company 등)
const SHARED_KAFKA_CONFIG = 'KafkaConsumerErrorHandlingConfig';

/** settings.gradle.kts 가 선언한 Gradle 모듈만 대상 — 폴리글랏 standalone 은 shared-common 자체가 없다. */
export function parseGradleModules(settingsText) {
  const includeBlock = /include\s*\(([\s\S]*?)\)/.exec(String(settingsText));
  if (!includeBlock) return [];
  return [...includeBlock[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

function gitGrepFiles(repoRoot, pattern, pathspecs) {
  // --untracked: 아직 커밋되지 않은 신규 배선 파일도 봐야 한다. 이게 없으면 "방금 추가한 @Import"를
  //   못 보고 오탐을 낸다(무시 대상 파일은 여전히 제외된다).
  // git grep -l 은 매치가 없으면 exit 1 — 정상 상태이므로 예외로 만들지 않는다.
  const result = spawnSync('git', ['grep', '-lE', '--untracked', '-e', pattern, '--', ...pathspecs], { cwd: repoRoot });
  if (result.status === 1) return [];
  if (result.status !== 0) throw new Error(`git grep failed: ${result.stderr?.toString('utf8') ?? ''}`);
  return result.stdout.toString('utf8').split(/\r?\n/).filter(Boolean).map((f) => f.replaceAll('\\', '/'));
}

const moduleOf = (file) => file.split('/')[0];

const ROOT_PACKAGE = 'github.lms.lemuel';
/** 컴포넌트 스캔 범위를 정하는 어노테이션만 — @EntityScan·@EnableJpaRepositories 의 동명 속성은 무관하다. */
const COMPONENT_SCAN_ANNOTATION = /@(?:ComponentScan|SpringBootApplication)\s*\(/g;
/** {@code scanBasePackages}/{@code basePackages} 의 값 리터럴만 뽑는다 — excludeFilters 의 정규식은 보지 않는다. */
const SCAN_ATTRIBUTE = /\b(?:scanBasePackages|basePackages)\s*=\s*(\{[^}]*\}|"[^"]*")/g;

/** 여는 괄호 위치에서 시작해 짝이 맞는 닫는 괄호까지의 인자 텍스트를 돌려준다. */
function balancedArgs(source, openParenIndex) {
  let depth = 0;
  for (let i = openParenIndex; i < source.length; i += 1) {
    if (source[i] === '(') depth += 1;
    else if (source[i] === ')') {
      depth -= 1;
      if (depth === 0) return source.slice(openParenIndex + 1, i);
    }
  }
  return source.slice(openParenIndex + 1);
}

/**
 * 부트 진입점이 루트 패키지 전체를 컴포넌트 스캔하는지 판정한다.
 *
 * <p>스캔 범위 지정이 아예 없으면 진입점의 패키지(=루트)부터 스캔하므로 참이다. 지정이 있으면
 * 값이 전부 {@code github.lms.lemuel} 일 때만 참 — {@code github.lms.lemuel.company} 처럼
 * 하위로 좁힌 경우는 shared-common 의 공용 설정이 스캔에 안 잡히므로 거짓이다.
 *
 * <p>주의 2가지(둘 다 실제로 오탐을 냈다):
 * <ul>
 *   <li>account 는 {@code @ComponentScan(basePackages = "github.lms.lemuel", excludeFilters = ...)} 로
 *       일부만 배제한다 — 여전히 루트 스캔이다.</li>
 *   <li>같은 파일의 {@code @EntityScan}/{@code @EnableJpaRepositories} 도 {@code basePackages} 를 쓰지만
 *       JPA 스캔이라 컴포넌트 스캔 범위와 무관하다 — 그래서 어노테이션 블록 안으로 한정해 읽는다.</li>
 * </ul>
 */
export function isRootScanned(source) {
  const text = String(source);
  const packages = [];
  for (const annotation of text.matchAll(COMPONENT_SCAN_ANNOTATION)) {
    const args = balancedArgs(text, annotation.index + annotation[0].length - 1);
    for (const attribute of args.matchAll(SCAN_ATTRIBUTE)) {
      packages.push(...[...attribute[1].matchAll(/"([^"]*)"/g)].map((q) => q[1]));
    }
  }
  return packages.length === 0 || packages.every((pkg) => pkg === ROOT_PACKAGE);
}

/** 자체 DLT 격리 머시너리 — 폴리글랏 standalone 이 공용 설정 없이 같은 계약을 만족하는 경로. */
const OWN_DLT_WIRING = 'DeadLetterPublishingRecoverer';

/**
 * 주석(한 줄·블록)을 지운 소스를 돌려준다. 배선 판정은 **실행되는 코드**만 봐야 한다 —
 * 자바독의 {@code @Import(...)} 예시나 "언젠가 이렇게 배선하자"는 메모가 통과 근거가 되면
 * 애노테이션만 지우고 주석을 남긴 순간 가드가 조용히 GREEN 이 된다(= 가드의 존재 이유 상실).
 *
 * 문자열 리터럴은 보존한다 — `"https://..."` 의 `//` 를 주석 시작으로 오인하면 같은 줄 뒤의
 * 실제 배선 코드가 통째로 사라져 반대 방향 오탐이 난다.
 */
export function stripComments(source) {
  const text = String(source);
  let out = '';
  let state = 'code'; // code | line | block | string | char
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    const next = text[i + 1];
    if (state === 'code') {
      if (c === '/' && next === '/') { state = 'line'; i += 1; continue; }
      if (c === '/' && next === '*') { state = 'block'; i += 1; continue; }
      if (c === '"') state = 'string';
      else if (c === "'") state = 'char';
      out += c;
    } else if (state === 'line') {
      if (c === '\n') { state = 'code'; out += c; }
    } else if (state === 'block') {
      if (c === '*' && next === '/') { state = 'code'; i += 1; }
      else if (c === '\n') out += c; // 줄 수를 보존해 다른 규칙의 행 번호가 밀리지 않게 한다
    } else { // string | char
      if (c === '\\') { out += c + (next ?? ''); i += 1; continue; }
      if ((state === 'string' && c === '"') || (state === 'char' && c === "'")) state = 'code';
      out += c;
    }
  }
  return out;
}

/** 공용 배선을 실제로 끌어오는 형태 — 자바독 예시가 아니라 애노테이션이어야 한다. */
const IMPORT_SHARED_CONFIG = new RegExp(`@Import[\\s(]*\\{?[^)]*${SHARED_KAFKA_CONFIG}\\s*(?:\\.class|::class)`);
/** 자체 배선을 실제로 생성하는 형태 — 타입 이름 언급이 아니라 생성자 호출이어야 한다. */
const CONSTRUCTS_OWN_WIRING = new RegExp(`${OWN_DLT_WIRING}\\s*\\(`);

/** 후보 파일 중 실제 배선 코드를 가진 파일이 하나라도 있는 모듈 집합. */
function modulesWiredBy(candidateFiles, pattern, readSource) {
  const wired = new Set();
  for (const file of candidateFiles) {
    let source;
    try {
      source = readSource(file);
    } catch {
      continue; // 읽을 수 없는 후보는 근거로 삼지 않는다 — 통과시키지도 않는다
    }
    if (pattern.test(stripComments(source))) wired.add(moduleOf(file));
  }
  return wired;
}

/**
 * @KafkaListener 를 가진 모듈에 DLT 배선이 닿는지 검사한다.
 *
 * <p>배선이 닿는 경로는 셋 중 하나다.
 * <ol>
 *   <li><b>(a) 루트 스캔 + shared-common 의존</b> — 공용 설정이 자동으로 잡힌다(대부분의 Java 서비스).
 *       스캔만으로는 부족하다: shared-common 미의존 위성(financial·economics·market·commondata)은
 *       루트 스캔이어도 공용 설정이 클래스패스에 아예 없다.</li>
 *   <li><b>(b) 명시 @Import</b> — 제한 스캔 서비스(company 등). 주석은 근거가 되지 않는다:
 *       {@code stripComments} 후 애노테이션 형태가 남아 있어야 한다.</li>
 *   <li><b>(c) 자체 DLT 배선</b> — shared-common 을 의존하지 않는 폴리글랏 standalone
 *       (notification-service: Kotlin/Boot 3.x/JDK 21). 같은 계약을 자기 언어로 구현한다.</li>
 * </ol>
 *
 * <p>shared-common 자신은 배선 제공자이므로 (c) 로 자연히 통과한다.
 */
export function checkKafkaDlqWiring(repoRoot, deps = {}) {
  const grep = deps.gitGrepFiles ?? gitGrepFiles;
  const readSource = deps.readSource ?? ((f) => readFileSync(resolve(repoRoot, f), 'utf8'));
  // 워킹트리를 읽는다 — 모듈을 추가하는 커밋에서도 그 커밋이 곧바로 검사 대상이 되어야 한다.
  const readSettings = deps.readSettings
    ?? (() => readFileSync(resolve(repoRoot, 'settings.gradle.kts'), 'utf8'));

  let gradleModules;
  try {
    gradleModules = new Set(parseGradleModules(readSettings()));
  } catch {
    return []; // settings 를 못 읽는 환경(얕은 클론 등)에서 가드를 깨뜨리지 않는다
  }

  const mainSources = ['*/src/main/**/*.java', '*/src/main/**/*.kt'];
  // 폴리글랏 standalone 도 대상이다 — settings.gradle.kts 밖이라고 유실이 허용되지는 않는다.
  const consumerModules = new Set(grep(repoRoot, '@KafkaListener', mainSources).map(moduleOf));
  if (consumerModules.size === 0) return [];

  // git grep 은 후보 파일을 싸게 좁히는 용도일 뿐이다 — 통과 판정은 주석을 걷어낸 뒤
  // 실제 배선 형태(@Import 애노테이션 / recoverer 생성자 호출)가 있을 때만 내린다.
  const ownWiringModules = modulesWiredBy(grep(repoRoot, OWN_DLT_WIRING, mainSources), CONSTRUCTS_OWN_WIRING, readSource);
  const importingModules = modulesWiredBy(grep(repoRoot, SHARED_KAFKA_CONFIG, mainSources), IMPORT_SHARED_CONFIG, readSource);
  // account 처럼 @SpringBootApplication 을 분해해 쓰는 형태(@SpringBootConfiguration +
  // @EnableAutoConfiguration + @ComponentScan(excludeFilters=...))도 진입점으로 인정한다.
  const rootScanned = new Set(
    grep(repoRoot, '@SpringBoot(Application|Configuration)', mainSources)
      .filter((f) => /\/src\/main\/java\/github\/lms\/lemuel\/[^/]+\.java$/.test(f))
      .filter((f) => isRootScanned(readSource(f)))
      .map(moduleOf),
  );

  const dependsOnSharedCommon = (module) => {
    if (!gradleModules.has(module)) return false;
    try {
      return readSource(`${module}/build.gradle.kts`).includes('shared-common');
    } catch {
      return false;
    }
  };

  const violations = [];
  for (const module of [...consumerModules].sort()) {
    if (ownWiringModules.has(module) || importingModules.has(module)) continue;
    if (rootScanned.has(module) && dependsOnSharedCommon(module)) continue;
    violations.push({
      file: `${module}/src/main`,
      id: 'KAFKA-DLQ',
      msg: `@KafkaListener 가 있는데 DLT 배선이 닿지 않음 — Spring Kafka 기본 핸들러는 재시도 소진 후 메시지를 조용히 skip 한다(사실상 유실). shared-common 의존 서비스는 루트 스캔이거나 @Import(${SHARED_KAFKA_CONFIG}.class), standalone 은 자체 ${OWN_DLT_WIRING} 배선이 필요하다`,
    });
  }
  return violations;
}

export function discoverStagedDeletions(repoRoot) {
  const output = execFileSync('git', ['diff', '--cached', '--name-only', '-z', '--diff-filter=D'], { cwd: repoRoot });
  return output.toString('utf8').split('\0').filter(Boolean);
}

export function discoverStagedFiles(repoRoot) {
  const output = execFileSync('git', ['diff', '--cached', '--name-only', '-z', '-M', '--diff-filter=ACMR'], { cwd: repoRoot });
  return output.toString('utf8').split('\0').filter(Boolean);
}

function hasApplicableRule(f) {
  const comparablePath = policyPath(f);
  return RULES.some((rule) => rule.when(comparablePath));
}

async function scanRepoFile(repoRoot, requestedPath) {
  // Rules only target java/kt/sql sources — skip reading anything else so staged
  // binary artifacts (docx/png/log) can't abort the whole scan on a UTF-8 decode failure.
  if (!hasApplicableRule(requestedPath)) return [];
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
  const modes = ['--staged', '--list', '--deleted-list', '--files', '--hook', '--self-test'].filter((mode) => args.includes(mode));
  if (modes.length !== 1) { stderr('exactly one guard mode is required'); return 2; }
  const mode = modes[0];
  // 인자 검증 실패를 종료 코드만으로 알리면 CI 로그에는 "exit 1" 만 남아 원인 추적이 불가능하다.
  // 어떤 모드가 무엇을 요구하는지 항상 stderr 로 말한다.
  const usage = (spec, code) => { stderr(`usage: guard ${spec}`); return code; };
  if (mode === '--self-test') {
    if (args.length !== 1) return usage('--self-test', 2);
    return spawnSync(process.execPath, ['--test', fileURLToPath(new URL('./test/guard.test.mjs', import.meta.url))], { cwd: repoRoot, stdio: 'inherit' }).status ?? 2;
  }
  if (mode === '--deleted-list') {
    // CI 는 삭제를 --diff-filter=ACMR 로 걸러 changed.txt 에 담지 않는다. 삭제 목록을 따로 받아
    // 하네스 보호 경로가 지워졌는지 검사한다 — PR 로 들어온 대량 삭제를 막는 유일한 지점이다.
    if (args.length !== 2) return usage('--deleted-list <file>', 2);
    try {
      const listPath = await normalizeRepoPath(repoRoot, args[1]);
      const deleted = (await readUtf8Strict(listPath)).split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
      return emitReport(checkProtectedDeletions(deleted), io);
    } catch (error) { stderr(`guard input failed: ${error.message}`); return 1; }
  }
  if (mode === '--hook') {
    if (args.length !== 1) return usage('--hook   (PreToolUse 이벤트 JSON 을 stdin 으로)', 2);
    try {
      const event = JSON.parse(io.stdin ?? await readStdinUtf8Strict());
      const pending = await reconstructPendingContent(event, { repoRoot });
      const { violations } = scanText(event.tool_input.file_path, pending);
      await logGuardHits(repoRoot, 'hook', violations); // observability only — never affects the verdict
      return emitReport(violations, io) ? 2 : 0;
    } catch (error) { stderr(`guard hook rejected input: ${error.message}`); return 2; }
  }
  try {
    let files;
    if (mode === '--staged') {
      if (args.length !== 1) return usage('--staged', 2);
      files = discoverStagedFiles(repoRoot);
    } else if (mode === '--list') {
      if (args.length !== 2) return usage('--list <file>   (검사할 파일 목록이 줄 단위로 담긴 파일)', 1);
      const listPath = await normalizeRepoPath(repoRoot, args[1]);
      files = (await readUtf8Strict(listPath)).split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
    } else {
      if (args[0] !== '--files' || args.length < 2) return usage('--files <file> [file...]', 1);
      files = args.slice(1);
    }
    const violations = [];
    for (const file of files) violations.push(...await scanRepoFile(repoRoot, file));
    // 삭제는 내용 스캔으로 잡히지 않는다(스테이징 목록이 ACMR 로 D 를 빼고 온다) — 별도로 확인한다.
    if (mode === '--staged') violations.push(...checkProtectedDeletions(discoverStagedDeletions(repoRoot)));
    // "없는 파일"은 라인 스캔으로 못 잡는다 — 저장소 단위 불변식은 커밋·CI 시점에 전수 검사한다.
    // (--files/--hook 은 편집 중 실시간 경로라 저장소 전수 스캔을 돌리지 않는다.)
    if (mode === '--staged' || mode === '--list') violations.push(...checkKafkaDlqWiring(repoRoot));
    await logGuardHits(repoRoot, mode.slice(2), violations); // observability only — never affects the verdict
    if (mode === '--staged') {
      const nudge = dodNudgeMessage(files);
      if (nudge) {
        stderr(nudge); // advisory only — exit code stays with emitReport
        await appendJsonl(repoRoot, 'dod-nudges.jsonl', [{ ts: new Date().toISOString(), staged: files.length }]);
      }
    }
    return emitReport(violations, io);
  } catch (error) { stderr(`guard input failed: ${error.message}`); return 1; }
}


if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  process.exit(await runGuardCli(process.argv.slice(2)));
}
