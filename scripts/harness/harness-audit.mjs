#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const REQUIRED_CASES = [
  'seed-gate-create',
  'seed-gate-reuse',
  'user-adoption',
  'first-cycle-skip',
  'threshold-boundary',
  'safety-cycle-5',
];
const CANONICAL_CONTRACT_CASES = {
  'seed-gate-create': 'incomplete-seed->socrates',
  'seed-gate-reuse': 'complete-seed->evolve-step',
  'user-adoption': 'candidate-requires-explicit-user-approval',
  'first-cycle-skip': 'cycle-1->skip-comparison',
  'threshold-boundary': 'similarity>=0.85->convergence',
  'safety-cycle-5': 'cycle-5-not-converged->safety_valve',
};

function assertPath(value, label) {
  if (typeof value !== 'string' || value.length === 0 || isAbsolute(value) || value.includes('\\') || value.split('/').includes('..')) {
    throw new Error(`manifest ${label} must be a non-empty repository-relative POSIX path`);
  }
}

export function validateManifest(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || value.schemaVersion !== 1) {
    throw new Error('manifest schemaVersion must be 1');
  }
  if (!Array.isArray(value.requiredTrackedFiles) || !Array.isArray(value.criticalContractPairs)) {
    throw new Error('manifest arrays are required');
  }
  const seen = new Set();
  for (const path of value.requiredTrackedFiles) {
    assertPath(path, 'requiredTrackedFiles entry');
    if (seen.has(path)) throw new Error(`manifest duplicate path: ${path}`);
    seen.add(path);
  }
  for (const pair of value.criticalContractPairs) {
    if (!pair || typeof pair !== 'object' || typeof pair.contract !== 'string' || !pair.contract) {
      throw new Error('manifest contract pair is invalid');
    }
    assertPath(pair.claude, `${pair.contract}.claude`);
    assertPath(pair.codex, `${pair.contract}.codex`);
    if (!seen.has(pair.claude) || !seen.has(pair.codex)) {
      throw new Error(`manifest contract files must be required tracked files: ${pair.contract}`);
    }
    if (!Object.hasOwn(pair, 'facts') && !deepEqual(pair.contractCases, CANONICAL_CONTRACT_CASES)) {
      throw new Error(`manifest ${pair.contract} must contain the exact canonical contract cases`);
    }
  }
  return value;
}

export function extractHarnessContract(markdown) {
  const blocks = [...String(markdown).matchAll(/```harness-contract\s*\r?\n([\s\S]*?)\r?\n```/g)];
  if (blocks.length !== 1) throw new Error(`harness-contract block count must be 1, got ${blocks.length}`);
  try {
    return JSON.parse(blocks[0][1]);
  } catch (error) {
    throw new Error(`harness-contract JSON is invalid: ${error.message}`);
  }
}

export function readContractCases(json) {
  let value;
  try {
    value = JSON.parse(json);
  } catch (error) {
    throw new Error(`contract cases JSON is invalid: ${error.message}`);
  }
  const cases = Array.isArray(value) ? value : value?.cases;
  if (!Array.isArray(cases)) throw new Error('contract cases must be an array or a cases array');
  const result = {};
  for (const item of cases) {
    if (!item || typeof item.contractCase !== 'string' || typeof item.expectedTransition !== 'string') {
      throw new Error('contract case requires contractCase and expectedTransition strings');
    }
    if (Object.hasOwn(result, item.contractCase)) throw new Error(`duplicate contract case: ${item.contractCase}`);
    result[item.contractCase] = item.expectedTransition;
  }
  for (const id of REQUIRED_CASES) if (!Object.hasOwn(result, id)) throw new Error(`missing contract case: ${id}`);
  for (const id of Object.keys(result)) if (!REQUIRED_CASES.includes(id)) throw new Error(`unexpected contract case: ${id}`);
  return result;
}

function deepEqual(a, b) {
  if (Object.is(a, b)) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
  const ak = Object.keys(a);
  const bk = Object.keys(b);
  return ak.length === bk.length && ak.every((key) => Object.hasOwn(b, key) && deepEqual(a[key], b[key]));
}

function trackedFiles(repoRoot) {
  const raw = execFileSync('git', ['-C', repoRoot, 'ls-files', '-z'], { encoding: 'utf8' });
  return raw.split('\0').filter(Boolean).sort();
}

function claimNumber(status, patterns) {
  for (const pattern of patterns) {
    const match = status.match(pattern);
    if (match) return Number(match[1].replaceAll(',', ''));
  }
  return null;
}

export function parseGradleModules(settings) {
  // "includeBuild(" 은 "include(" 부분문자열을 포함하지 않으므로 오매치 없음
  const block = String(settings).match(/include\(([\s\S]*?)\)/);
  if (!block) return [];
  return [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

// settings.gradle.kts 모듈 로스터 ↔ 문서 트리(CLAUDE.md·STRUCTURE.md) 대조 —
// 서비스 모듈을 추가/삭제하고 문서 트리를 안 고치면 audit 이 실패한다.
function validateModuleRoster(read, trackedSet, errors) {
  if (!trackedSet.has('settings.gradle.kts')) return;
  const modules = parseGradleModules(read('settings.gradle.kts'));
  for (const doc of ['CLAUDE.md', 'STRUCTURE.md']) {
    if (!trackedSet.has(doc)) continue;
    const content = read(doc);
    for (const module of modules) {
      if (!content.includes(module)) {
        errors.push(`${doc} module roster missing: ${module} (declared in settings.gradle.kts)`);
      }
    }
  }
}

// HARNESS.md 라우팅 맵(🤖📘⌘ 아이콘 줄)의 backtick 진입점 토큰을 실존 검증 —
// 에이전트·스킬·커맨드를 삭제/개명하고 라우팅 맵을 안 고치면 audit 이 실패한다.
// 토큰 규칙: `name`(agents/skills/commands 중 하나) · `/name`(커맨드 전용).
// 점(.)·슬래시(/)·중괄호가 섞인 토큰(파일명·플레이스홀더)은 대상 밖 — 수동 스니펫과 동일 스코프.
export function parseRoutingEntrypoints(markdown) {
  const tokens = new Set();
  for (const line of String(markdown).split(/\r?\n/)) {
    if (!/[🤖📘⌘]/u.test(line)) continue;
    for (const match of line.matchAll(/`(\/?[a-z][a-z0-9-]+)`/g)) tokens.add(match[1]);
  }
  return [...tokens];
}

function validateRoutingMap(read, trackedSet, errors) {
  if (!trackedSet.has('HARNESS.md')) return;
  for (const token of parseRoutingEntrypoints(read('HARNESS.md'))) {
    const name = token.startsWith('/') ? token.slice(1) : token;
    const command = trackedSet.has(`.claude/commands/${name}.md`);
    const resolved = token.startsWith('/')
      ? command
      : command || trackedSet.has(`.claude/agents/${name}.md`) || trackedSet.has(`.claude/skills/${name}/SKILL.md`);
    if (!resolved) errors.push(`HARNESS.md routing map dangling: ${token} (진입점이 agents/skills/commands 에 없음)`);
  }
}

// pre-commit Layer 2(copilot 플러그인 가드) 경로 ↔ 실제 추적 위치 대조 —
// 훅은 미존재 경로를 조용히 skip 하므로(`[ -f ]`), 플러그인 트리를 옮기고 훅 목록을 안 고치면
// 가드가 사라진 것도 모른 채 커밋이 통과한다. 플러그인이 추적되지 않는 저장소(플러그인 독립
// fresh clone)에서는 대조 대상이 없으므로 건너뛴다.
export function parseHookPluginGuards(hook) {
  return [...String(hook).matchAll(/"([\w./-]*pre-commit\.mjs)"/g)].map((m) => m[1]);
}

function validatePluginGuardPaths(read, tracked, trackedSet, errors) {
  if (!trackedSet.has('scripts/harness/hooks/pre-commit')) return;
  const guards = tracked.filter((p) => /(?:^|\/)(?:settlement|invest)-copilot\/hooks\/guards\/pre-commit\.mjs$/.test(p));
  if (guards.length === 0) return;
  const referenced = new Set(parseHookPluginGuards(read('scripts/harness/hooks/pre-commit')));
  for (const guard of guards) {
    if (!referenced.has(guard)) {
      errors.push(`pre-commit Layer 2 경로 드리프트: ${guard} (훅 목록에 없어 조용히 skip 됨)`);
    }
  }
}

// 문서 → 저장소 노드 간선. 마크다운 링크 `[텍스트](경로)` 의 대상이 추적 그래프 안에 있는지 본다.
// 링크 대상만 보고 backtick 경로는 보지 않는다 — 산문 속 경로는 예시·플레이스홀더가 섞여 소음이 크다.
// 제목(`[x](a.md "제목")`)은 잘라내고, 외부 URL·앵커·플레이스홀더(<>{}*$)는 대상이 아니다.
export function parseDocLinks(markdown) {
  const targets = [];
  for (const match of String(markdown).matchAll(/\]\(([^)\n]+)\)/g)) {
    const target = match[1].trim().split(/\s+/)[0];
    if (!target || target.startsWith('#')) continue;
    if (target.includes('://') || target.startsWith('mailto:')) continue;
    if (/[<>{}*$]/.test(target)) continue;
    targets.push(target);
  }
  return targets;
}

// 문서 기준 상대경로를 저장소 루트 기준으로 접는다. OS 경로 API 를 쓰지 않는다 —
// 저장소 경로는 항상 POSIX 이고, Windows 에서 path.join 을 태우면 구분자가 섞인다.
function resolveDocTarget(doc, target) {
  const out = [];
  for (const segment of [...doc.split('/').slice(0, -1), ...target.split('/')]) {
    if (!segment || segment === '.') continue;
    if (segment === '..') {
      if (out.length === 0) return null; // 저장소 밖 — 판정 대상 아님
      out.pop();
      continue;
    }
    out.push(segment);
  }
  return out.join('/');
}

/**
 * 링크가 추적 그래프를 벗어나면 보고한다. 두 유형을 구분하는 것이 이 검사의 핵심이다.
 *
 * <p>`dangling` 은 대상이 아예 없는 경우고, `untracked` 는 디스크에는 있지만 추적되지 않는 경우다.
 * 후자가 더 위험하다 — 작성자 화면에서는 링크가 열리므로 육안 리뷰로 절대 잡히지 않고,
 * clone 한 사람에게만 깨진다. 실제로 gitignore 된 경로를 "정본"으로 가리키는 문서가 나왔다.
 */
function validateDocLinks(root, read, tracked, trackedSet, manifest, errors) {
  const ignorePrefixes = manifest.docLinkIgnorePrefixes ?? [];
  const dirs = new Set();
  for (const path of tracked) {
    const parts = path.split('/');
    for (let i = 1; i < parts.length; i += 1) dirs.add(parts.slice(0, i).join('/'));
  }
  // 에이전트 지시서(.claude/·.codex/)는 스캔하지 않는다 — 그 링크는 저장소 참조가 아니라
  // "이런 파일을 만들어라"는 산출물 명세인 경우가 많아, 검사하면 템플릿마다 예외를 등록하게 된다.
  // 그 트리의 참조 무결성은 위 referenceSources 검사(scripts/harness 경로)가 담당한다.
  for (const doc of tracked.filter((p) => p.endsWith('.md') && !/^\.(claude|codex)\//.test(p))) {
    let text;
    try {
      text = read(doc);
    } catch {
      continue;
    }
    for (const raw of parseDocLinks(text)) {
      const target = raw.split('#')[0].split('?')[0];
      if (!target) continue;
      const resolved = resolveDocTarget(doc, target);
      if (resolved === null || resolved === '') continue;
      if (ignorePrefixes.some((prefix) => resolved.startsWith(prefix))) continue;
      if (trackedSet.has(resolved) || dirs.has(resolved)) continue;
      errors.push(existsSync(resolve(root, ...resolved.split('/')))
        ? `doc link untracked: ${doc} → ${target} (디스크에만 존재 — 로컬에서만 열리고 clone 하면 깨진다)`
        : `doc link dangling: ${doc} → ${target} (대상 없음)`);
    }
  }
}

// 서비스 → 배선 간선. 배선 누락은 컴파일이 잡아주지 않고 런타임에 조용히 404/500 으로 나온다
// (📘msa-service-wiring, 실사고 36ac0234). 5곳 중 기계로 확정 가능한 3곳만 본다 —
// nginx 는 배포 형태가 여러 벌이라 단일 정본이 없고, JPA 스캔은 제한 스캔 서비스마다 정책이 달라
// 오탐이 난다. 나머지 2곳은 스킬 체크리스트가 담당한다.

// 루트 Dockerfile 은 의존 COPY(build.gradle.kts)와 소스 COPY 두 벌을 요구한다.
// 소스 COPY 가 빠지면 settings 평가는 통과하고 빌드가 뒤에서 깨진다 — 둘 다 있어야 배선이다.
export function parseDockerfileModules(dockerfile) {
  const text = String(dockerfile);
  const deps = new Set([...text.matchAll(/^COPY\s+([\w-]+)\/build\.gradle\.kts/gm)].map((m) => m[1]));
  const sources = new Set([...text.matchAll(/^COPY\s+([\w-]+)\s+\.\/[\w-]+/gm)].map((m) => m[1]));
  return [...deps].filter((module) => sources.has(module));
}

export function parseGatewayRouteIds(yaml) {
  return [...String(yaml).matchAll(/^\s*-\s*id:\s*(\S+)/gm)].map((m) => m[1]);
}

export function parseScanBasePackages(java) {
  const match = String(java).match(/scanBasePackages\s*=\s*(\{[^}]*\}|"[^"]+")/);
  return match ? [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]) : [];
}

const SPRING_STEREOTYPE = /@(RestController|Controller|Component|Service|Repository|Configuration|ControllerAdvice|ConfigurationProperties)\b/;
const DOMAIN_BASE = 'src/main/java/github/lms/lemuel/';

function validateServiceWiring(read, tracked, trackedSet, errors) {
  if (!trackedSet.has('settings.gradle.kts')) return;
  const modules = parseGradleModules(read('settings.gradle.kts'));

  if (trackedSet.has('Dockerfile')) {
    const copied = new Set(parseDockerfileModules(read('Dockerfile')));
    for (const module of modules) {
      if (!copied.has(module)) {
        errors.push(`service wiring: Dockerfile COPY 누락: ${module} (settings 평가가 실패해 전체 이미지 빌드가 깨진다)`);
      }
    }
  }

  const gatewayYml = 'gateway-service/src/main/resources/application.yml';
  if (trackedSet.has(gatewayYml)) {
    const ids = parseGatewayRouteIds(read(gatewayYml));
    for (const module of modules) {
      if (module === 'gateway-service') continue;
      // 라우트 id 는 모듈명 그대로이거나 접미가 붙는다(order-service-orders 처럼 경로군 분리).
      if (!ids.some((id) => id === module || id.startsWith(`${module}-`))) {
        errors.push(`service wiring: gateway 라우트 누락: ${module} (직접 포트는 되고 8080 경유만 404)`);
      }
    }
  }

  // 스프링 빈을 가진 도메인 패키지가 scanBasePackages 에 없으면 핸들러가 등록되지 않는다.
  // 스테레오타입이 없는 패키지(마커·DTO·순수 도메인)는 요구하지 않는다 — 요구하면 오탐만 는다.
  for (const module of modules) {
    const appPath = tracked.find((p) => p.startsWith(`${module}/src/main/java/`) && p.endsWith('Application.java'));
    if (!appPath) continue;
    const scanned = parseScanBasePackages(read(appPath));
    if (scanned.length === 0) continue; // 미선언 = 애플리케이션 패키지 이하 기본 스캔
    const base = `${module}/${DOMAIN_BASE}`;
    const packages = new Set();
    for (const path of tracked) {
      if (!path.startsWith(base) || !path.endsWith('.java')) continue;
      const rest = path.slice(base.length).split('/');
      if (rest.length > 1) packages.add(rest[0]);
    }
    for (const pkg of [...packages].sort()) {
      const full = `github.lms.lemuel.${pkg}`;
      if (scanned.some((s) => s === full || s === 'github.lms.lemuel' || full.startsWith(`${s}.`))) continue;
      const owned = tracked.filter((p) => p.startsWith(`${base + pkg}/`) && p.endsWith('.java'));
      if (owned.some((p) => SPRING_STEREOTYPE.test(read(p)))) {
        errors.push(`service wiring: scanBasePackages 누락: ${module} → ${full} (빈 미등록 — 핸들러 404 / 리포지토리 500)`);
      }
    }
  }
}

function validateStatus(status, tracked, errors) {
  const checks = [
    ['application.yml', tracked.filter((p) => /\/src\/main\/resources\/application\.yml$/.test(p)).length, [/application\.yml[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i, /application\.yml[^\n]*?→\s*(\d[\d,]*)/i]],
    ['migration', tracked.filter((p) => /\/src\/main\/resources\/db\/migration\/.*\.sql$/.test(p)).length, [/(?:Flyway|migration|마이그레이션)[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i]],
    ['ADR', tracked.filter((p) => /^docs\/adr\/\d.*\.md$/.test(p)).length, [/ADR[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i]],
    ['test classes', tracked.filter((p) => /\/src\/test\/.*(?:Test|Tests|IT)\.java$/.test(p)).length, [/(?:test classes|테스트 클래스)[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i]],
  ];
  for (const [label, actual, patterns] of checks) {
    const claimed = claimNumber(status, patterns);
    if (claimed === null) errors.push(`STATUS ${label} claim missing (actual=${actual})`);
    else if (claimed !== actual) errors.push(`STATUS ${label} mismatch: claimed=${claimed} actual=${actual}`);
  }
}

export function collectAudit(repoRoot, manifest) {
  validateManifest(manifest);
  const root = resolve(repoRoot);
  const tracked = trackedFiles(root);
  const trackedSet = new Set(tracked);
  const errors = [];
  const read = (path) => readFileSync(resolve(root, ...path.split('/')), 'utf8');

  for (const path of manifest.requiredTrackedFiles) {
    if (!existsSync(resolve(root, ...path.split('/')))) errors.push(`${path}: required file missing`);
    else if (!trackedSet.has(path)) errors.push(`${path}: required file not tracked`);
  }

  const referenceSources = tracked.filter((p) => /^\.claude\/commands\/.*\.md$/.test(p));
  if (trackedSet.has('.claude/settings.json')) referenceSources.push('.claude/settings.json');
  for (const source of referenceSources) {
    const refs = new Set([...read(source).matchAll(/scripts\/harness\/[\w./-]+\.(?:mjs|sh)/g)].map((m) => m[0]));
    for (const ref of refs) if (!trackedSet.has(ref)) errors.push(`broken reference in ${source}: ${ref} is not tracked`);
  }

  if (trackedSet.has('STATUS.md')) validateStatus(read('STATUS.md'), tracked, errors);
  validateModuleRoster(read, trackedSet, errors);
  validateRoutingMap(read, trackedSet, errors);
  validatePluginGuardPaths(read, tracked, trackedSet, errors);
  validateDocLinks(root, read, tracked, trackedSet, manifest, errors);
  validateServiceWiring(read, tracked, trackedSet, errors);

  for (const pair of manifest.criticalContractPairs) {
    if (!trackedSet.has(pair.claude) || !trackedSet.has(pair.codex)) continue;
    try {
      if (Object.hasOwn(pair, 'facts')) {
        const claude = extractHarnessContract(read(pair.claude));
        const codex = extractHarnessContract(read(pair.codex));
        if (!deepEqual(claude, pair.facts) || !deepEqual(codex, pair.facts)) errors.push(`${pair.contract} facts mismatch`);
      } else {
        const claude = readContractCases(read(pair.claude));
        const codex = readContractCases(read(pair.codex));
        if (!deepEqual(claude, pair.contractCases)) errors.push(`${pair.contract} claude contract cases mismatch`);
        if (!deepEqual(codex, pair.contractCases)) errors.push(`${pair.contract} codex contract cases mismatch`);
      }
    } catch (error) {
      errors.push(`${pair.contract}: ${error.message}`);
    }
  }

  // 컨텍스트 예산(KPI-5): 세션마다 강제 로드되는 상주 문서 vs 온디맨드 스킬의 바이트.
  // 상주 비중이 늘면 온디맨드 설계(스킬 분리)가 무너지고 있다는 신호 — 정보 지표, 게이트 아님.
  const bytesOf = (path) => {
    try { return trackedSet.has(path) ? Buffer.byteLength(read(path), 'utf8') : 0; } catch { return 0; }
  };
  const skillFiles = tracked.filter((p) => /^\.claude\/skills\/.*\/SKILL\.md$/.test(p));
  const contextBudget = {
    residentBytes: bytesOf('CLAUDE.md'),
    onDemandSkillCount: skillFiles.length,
    onDemandSkillBytes: skillFiles.reduce((sum, path) => sum + bytesOf(path), 0),
  };

  return {
    checks: [],
    failures: errors,
    errors,
    contextBudget,
    inventory: {
      trackedFiles: tracked,
      agents: tracked.filter((p) => /^\.claude\/agents\/.*\.md$/.test(p)).length,
      skills: skillFiles.length,
      commands: tracked.filter((p) => /^\.claude\/commands\/.*\.md$/.test(p)).length,
    },
  };
}

export async function runAuditCli(args, io = {}) {
  const stdout = io.stdout ?? ((text) => process.stdout.write(text));
  const stderr = io.stderr ?? ((text) => process.stderr.write(text));
  const value = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index === -1 ? fallback : args[index + 1];
  };
  try {
    for (let index = 0; index < args.length; index += 2) {
      if (!['--root', '--manifest'].includes(args[index])) throw new Error(`unsupported argument: ${args[index]}`);
      if (!args[index + 1] || args[index + 1].startsWith('--')) throw new Error(`missing value for argument: ${args[index]}`);
    }
    const root = resolve(value('--root', process.cwd()));
    const manifestPath = value('--manifest', 'scripts/harness/manifest.json');
    assertPath(manifestPath, '--manifest');
    const manifest = validateManifest(JSON.parse(readFileSync(resolve(root, ...manifestPath.split('/')), 'utf8')));
    const result = collectAudit(root, manifest);
    for (const error of result.errors) stdout(`FAIL ${error}\n`);
    const { residentBytes, onDemandSkillCount, onDemandSkillBytes } = result.contextBudget;
    const totalBytes = residentBytes + onDemandSkillBytes;
    const kb = (bytes) => `${(bytes / 1024).toFixed(1)}KB`;
    stdout(`info resident-context: 상주 CLAUDE.md ${kb(residentBytes)} · 온디맨드 스킬 ${onDemandSkillCount}개 ${kb(onDemandSkillBytes)} (상주 비중 ${totalBytes ? Math.round((residentBytes / totalBytes) * 100) : 0}%)\n`);
    stdout(result.errors.length ? `harness-audit: ${result.errors.length} failure(s)\n` : 'harness-audit: healthy\n');
    return result.errors.length ? 1 : 0;
  } catch (error) {
    stderr(`harness-audit: ${error.message}\n`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runAuditCli(process.argv.slice(2));
}
