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
