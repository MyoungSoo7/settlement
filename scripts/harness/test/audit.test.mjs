import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

import {
  collectAudit,
  extractHarnessContract,
  readContractCases,
  runAuditCli,
  validateManifest,
} from '../harness-audit.mjs';

const FACTS = { maxCycles: 5, canonicalScratch: '.symposium/scratch/socrates.md' };
const CASES = [
  ['seed-gate-create', 'incomplete-seed->socrates'],
  ['seed-gate-reuse', 'complete-seed->evolve-step'],
  ['user-adoption', 'candidate-requires-explicit-user-approval'],
  ['first-cycle-skip', 'cycle-1->skip-comparison'],
  ['threshold-boundary', 'similarity>=0.85->convergence'],
  ['safety-cycle-5', 'cycle-5-not-converged->safety_valve'],
];

function baseManifest(files = []) {
  return {
    schemaVersion: 1,
    requiredTrackedFiles: files,
    criticalContractPairs: [],
  };
}

function git(root, ...args) {
  return execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' });
}

function put(root, path, content = '') {
  const full = join(root, ...path.split('/'));
  mkdirSync(join(full, '..'), { recursive: true });
  writeFileSync(full, content);
}

function repo(files, tracked = Object.keys(files)) {
  const root = mkdtempSync(join(tmpdir(), 'harness-audit-'));
  git(root, 'init', '-q');
  git(root, 'config', 'user.email', 'audit@example.test');
  git(root, 'config', 'user.name', 'Audit Test');
  for (const [path, content] of Object.entries(files)) put(root, path, content);
  if (tracked.length) {
    git(root, 'add', '--', ...tracked);
    git(root, 'commit', '-qm', 'fixture');
  }
  return root;
}

test('validateManifest accepts schema 1 and rejects invalid versions and paths', () => {
  assert.deepEqual(validateManifest(baseManifest(['a/b'])), baseManifest(['a/b']));
  for (const value of [
    { ...baseManifest(), schemaVersion: 2 },
    baseManifest(['a', 'a']),
    baseManifest(['/absolute']),
    baseManifest(['a/../b']),
    baseManifest(['a\\b']),
    baseManifest(['']),
  ]) assert.throws(() => validateManifest(value), /manifest/i);
});

test('validateManifest rejects missing contract files and malformed pair paths', () => {
  const pair = { claude: 'a.md', codex: 'b.md', contract: 'x', facts: {} };
  assert.throws(() => validateManifest({ ...baseManifest(['a.md']), criticalContractPairs: [pair] }), /manifest/i);
  assert.throws(() => validateManifest({ ...baseManifest(['a.md', 'b.md']), criticalContractPairs: [{ ...pair, codex: '..\\b' }] }), /manifest/i);
});

test('contract readers require one valid block and six unique cases', () => {
  const block = '```harness-contract\n{"maxCycles":5}\n```';
  assert.deepEqual(extractHarnessContract(block), { maxCycles: 5 });
  assert.throws(() => extractHarnessContract('none'), /harness-contract/i);
  assert.throws(() => extractHarnessContract(`${block}\n${block}`), /harness-contract/i);
  assert.throws(() => extractHarnessContract('```harness-contract\n{bad}\n```'), /JSON/i);
  assert.deepEqual(readContractCases(JSON.stringify(CASES.map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })))), Object.fromEntries(CASES));
  assert.throws(() => readContractCases(JSON.stringify(CASES.slice(1).map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })))), /seed-gate-create/);
  assert.throws(() => readContractCases(JSON.stringify([...CASES, ['extra', 'x']].map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })))), /unexpected contract case/i);
});

test('collectAudit uses tracked files only and reports missing and untracked required files', () => {
  const root = repo({ 'tracked.txt': 'yes', 'untracked.txt': 'no' }, ['tracked.txt']);
  const result = collectAudit(root, baseManifest(['tracked.txt', 'untracked.txt', 'missing.txt']));
  assert.deepEqual(result.inventory.trackedFiles, ['tracked.txt']);
  assert.match(result.errors.join('\n'), /untracked\.txt.*not tracked/i);
  assert.match(result.errors.join('\n'), /missing\.txt.*missing/i);
});

test('collectAudit validates command-script, settings-hook, STATUS claims, and inventory', () => {
  const status = [
    'application.yml **1개**',
    'migration **1개**',
    'ADR **1개**',
    'test classes **1개**',
  ].join('\n');
  const files = {
    '.claude/commands/check.md': 'node scripts/harness/tool.mjs\nsh scripts/harness/install.sh',
    '.claude/settings.json': '{"command":"node \\"$CLAUDE_PROJECT_DIR/scripts/harness/tool.mjs\\""}',
    'scripts/harness/tool.mjs': '',
    'scripts/harness/install.sh': '',
    'svc/src/main/resources/application.yml': '',
    'svc/src/main/resources/db/migration/V1__x.sql': '',
    'docs/adr/0001-x.md': '',
    'svc/src/test/java/XTest.java': '',
    'STATUS.md': status,
  };
  const root = repo(files);
  const ok = collectAudit(root, baseManifest(Object.keys(files)));
  assert.deepEqual(ok.errors, []);
  assert.deepEqual(ok.inventory, { trackedFiles: Object.keys(files).sort(), agents: 0, skills: 0, commands: 1 });

  put(root, '.claude/commands/check.md', 'node scripts/harness/gone.mjs\nsh scripts/harness/gone.sh');
  put(root, '.claude/settings.json', '{"command":"node \\"$CLAUDE_PROJECT_DIR/scripts/harness/gone.mjs\\""}');
  put(root, 'STATUS.md', status.replace('application.yml **1개**', 'application.yml **9개**'));
  const bad = collectAudit(root, baseManifest(Object.keys(files)));
  assert.match(bad.errors.join('\n'), /gone\.mjs/);
  assert.match(bad.errors.join('\n'), /gone\.sh/);
  assert.match(bad.errors.join('\n'), /application\.yml.*claimed=9.*actual=1/i);
});

test('collectAudit deep-compares both contract blocks and transition cases', () => {
  const skill = (facts) => `prose ignored\n\`\`\`harness-contract\n${JSON.stringify(facts)}\n\`\`\``;
  const cases = JSON.stringify(CASES.map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })));
  const files = { 'a.md': skill(FACTS), 'b.md': skill(FACTS), 'a.json': cases, 'b.json': cases };
  const manifest = {
    ...baseManifest(Object.keys(files)),
    criticalContractPairs: [
      { claude: 'a.md', codex: 'b.md', contract: 'skills', facts: FACTS },
      { claude: 'a.json', codex: 'b.json', contract: 'tests' },
    ],
  };
  const root = repo(files);
  assert.deepEqual(collectAudit(root, manifest).errors, []);
  put(root, 'b.md', skill({ ...FACTS, maxCycles: 6 }));
  assert.match(collectAudit(root, manifest).errors.join('\n'), /facts mismatch/i);
});

test('runAuditCli is injectable and returns a status without exiting the importer', async () => {
  const manifest = baseManifest(['manifest.json']);
  const root = repo({ 'manifest.json': JSON.stringify(manifest) });
  const output = [];
  assert.equal(await runAuditCli(['--root', root, '--manifest', 'manifest.json'], { stdout: (s) => output.push(s) }), 0);
  assert.match(output.join(''), /harness-audit: healthy/i);
  const errors = [];
  assert.equal(await runAuditCli(['--unknown'], { stderr: (s) => errors.push(s) }), 1);
  assert.match(errors.join(''), /unsupported argument/i);
});
