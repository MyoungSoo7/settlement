import assert from 'node:assert/strict';
import { chmodSync, cpSync, existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

import { findGitRoot, installHooks } from '../install-hooks.mjs';

const projectRoot = resolve(import.meta.dirname, '../../..');
const temporaryRoots = [];

function run(cwd, command, args, options = {}) {
  return spawnSync(command, args, { cwd, encoding: 'utf8', ...options });
}

function git(cwd, ...args) {
  return run(cwd, 'git', args);
}

function put(root, path, content) {
  const target = join(root, path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, content);
}

function createRepo() {
  const root = mkdtempSync(join(tmpdir(), 'harness-install-'));
  temporaryRoots.push(root);
  assert.equal(git(root, 'init').status, 0);
  assert.equal(git(root, 'config', 'user.name', 'Harness Test').status, 0);
  assert.equal(git(root, 'config', 'user.email', 'harness@example.test').status, 0);
  cpSync(join(projectRoot, 'scripts/harness/guard.mjs'), join(root, 'scripts/harness/guard.mjs'), { recursive: true });
  cpSync(join(projectRoot, 'scripts/harness/hooks/pre-commit'), join(root, 'scripts/harness/hooks/pre-commit'), { recursive: true });
  if (process.platform !== 'win32') chmodSync(join(root, 'scripts/harness/hooks/pre-commit'), 0o755);
  return root;
}

test.after(() => {
  for (const root of temporaryRoots) rmSync(root, { recursive: true, force: true });
});

test('findGitRoot resolves the repository from a nested directory', () => {
  const root = createRepo();
  const nested = join(root, 'one', 'two');
  mkdirSync(nested, { recursive: true });
  assert.equal(findGitRoot(nested), root);
});

test('installHooks is idempotent and only configures the tracked hook path', async () => {
  const root = createRepo();
  const nested = join(root, 'nested');
  mkdirSync(nested);
  const before = readFileSync(join(root, 'scripts/harness/hooks/pre-commit'), 'utf8');

  await installHooks({ cwd: nested, stdout: () => {}, stderr: () => {} });
  await installHooks({ cwd: nested, stdout: () => {}, stderr: () => {} });

  assert.equal(git(root, 'config', '--get', 'core.hooksPath').stdout.trim(), 'scripts/harness/hooks');
  assert.equal(readFileSync(join(root, 'scripts/harness/hooks/pre-commit'), 'utf8'), before);
  assert.equal(existsSync(join(root, '.git/hooks/pre-commit')), false);
});

test('installed hook allows a clean commit and rejects a pwc commit', async () => {
  const root = createRepo();
  await installHooks({ cwd: root, stdout: () => {}, stderr: () => {} });

  put(root, 'README.md', 'clean\n');
  assert.equal(git(root, 'add', 'README.md', 'scripts').status, 0);
  const clean = git(root, 'commit', '-m', 'clean');
  assert.equal(clean.status, 0, clean.stderr || clean.stdout);

  put(root, 'pwc/blocked.txt', 'blocked\n');
  assert.equal(git(root, 'add', 'pwc/blocked.txt').status, 0);
  const blocked = git(root, 'commit', '-m', 'blocked');
  assert.notEqual(blocked.status, 0, blocked.stdout);
  assert.match(`${blocked.stdout}\n${blocked.stderr}`, /pwc|blocking violation/i);
});

test('installed hook propagates failure from a present optional plugin guard', async () => {
  const root = createRepo();
  await installHooks({ cwd: root, stdout: () => {}, stderr: () => {} });
  put(root, 'hackathon/settlement-copilot/hooks/guards/pre-commit.mjs', "console.error('plugin rejected commit'); process.exit(23);\n");
  put(root, 'README.md', 'clean\n');
  assert.equal(git(root, 'add', 'README.md', 'scripts').status, 0);

  const result = git(root, 'commit', '-m', 'plugin failure');
  assert.notEqual(result.status, 0, result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /plugin rejected commit/);
});
