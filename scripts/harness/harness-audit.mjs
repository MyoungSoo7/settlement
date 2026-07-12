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

  return {
    checks: [],
    failures: errors,
    errors,
    inventory: {
      trackedFiles: tracked,
      agents: tracked.filter((p) => /^\.claude\/agents\/.*\.md$/.test(p)).length,
      skills: tracked.filter((p) => /^\.claude\/skills\/.*\/SKILL\.md$/.test(p)).length,
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
