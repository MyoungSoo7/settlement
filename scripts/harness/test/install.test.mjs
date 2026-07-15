import assert from 'node:assert/strict';
import {
  chmodSync,
  cpSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
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

function toPosixPath(path) {
  return path.split(sep).join('/');
}

function isPluginOrMcpPath(path) {
  return /(^|\/)(?:\.claude-plugin|\.codex-plugin|mcp)(?:\/|$)|(^|\/)\.mcp\.json$|^hackathon\/(?:settlement|invest)-copilot(?:\/|$)/.test(
    toPosixPath(path),
  );
}

function readTrackedBytes(path) {
  const result = spawnSync('git', ['-C', projectRoot, 'cat-file', 'blob', `HEAD:${path}`], {
    encoding: 'buffer',
    maxBuffer: 32 * 1024 * 1024,
  });
  assert.equal(result.status, 0, result.stderr?.toString() || path);
  assert.ok(result.stdout instanceof Buffer);
  return result.stdout;
}

function collectFilesystemPaths(root, relativeRoot = '') {
  const absoluteRoot = join(root, relativeRoot);
  if (!existsSync(absoluteRoot)) return [];

  return readdirSync(absoluteRoot, { withFileTypes: true }).flatMap((entry) => {
    const relativePath = toPosixPath(join(relativeRoot, entry.name));
    const absolutePath = join(root, relativePath);
    if (entry.isDirectory()) {
      return [relativePath, ...collectFilesystemPaths(root, relativePath)];
    }
    if (entry.isSymbolicLink()) {
      return [relativePath];
    }
    return existsSync(absolutePath) ? [relativePath] : [];
  });
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

function createFreshRepositorySnapshot() {
  const root = mkdtempSync(join(tmpdir(), 'harness-fresh-repository-'));
  temporaryRoots.push(root);
  const tracked = git(projectRoot, 'ls-tree', '-r', '--name-only', '-z', 'HEAD').stdout
    .split('\0')
    .filter((path) => path && !isPluginOrMcpPath(path));
  for (const path of tracked) {
    const target = join(root, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, readTrackedBytes(path));
  }
  assert.equal(git(root, 'init').status, 0);
  assert.equal(git(root, 'config', 'user.name', 'Harness Fresh Repository Test').status, 0);
  assert.equal(git(root, 'config', 'user.email', 'harness-fresh@example.test').status, 0);
  assert.equal(git(root, 'add', '--all').status, 0);
  assert.equal(git(root, 'commit', '--no-verify', '-m', 'fresh repository fixture').status, 0);
  assert.deepEqual(collectFilesystemPaths(root).filter(isPluginOrMcpPath), []);
  return root;
}

function childHarnessTests(root) {
  const testDirectory = join(root, 'scripts/harness/test');
  return cpTestPaths(testDirectory).map((path) => relative(root, path).split(sep).join('/'));
}

function cpTestPaths(directory) {
  return ['audit.test.mjs', 'guard.test.mjs', 'install.test.mjs']
    .map((name) => join(directory, name))
    .filter(existsSync);
}

test.after(() => {
  for (const root of temporaryRoots) rmSync(root, { recursive: true, force: true });
});

test('repository-owned harness documentation does not reference the legacy shell installer', () => {
  const documentation = ['CLAUDE.md', 'HARNESS.md', '.claude/commands/harness-check.md'];
  const staleReferences = documentation.flatMap((path) =>
    readFileSync(join(projectRoot, path), 'utf8')
      .split(/\r?\n/)
      .flatMap((line, index) => line.includes('install-hooks.sh') ? [`${path}:${index + 1}`] : []));

  assert.deepEqual(staleReferences, []);
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

test('installed hook allows clean commits including pwc submission paths', async () => {
  const root = createRepo();
  await installHooks({ cwd: root, stdout: () => {}, stderr: () => {} });

  put(root, 'README.md', 'clean\n');
  assert.equal(git(root, 'add', 'README.md', 'scripts').status, 0);
  const clean = git(root, 'commit', '-m', 'clean');
  assert.equal(clean.status, 0, clean.stderr || clean.stdout);

  // 2026-07-15 정책: 제출물은 원격 실행을 위해 커밋 대상 (구 NO-COMMIT 규칙 제거).
  put(root, 'pwc/submission/tracked.txt', 'tracked\n');
  assert.equal(git(root, 'add', 'pwc/submission/tracked.txt').status, 0);
  const submission = git(root, 'commit', '-m', 'submission');
  assert.equal(submission.status, 0, submission.stderr || submission.stdout);
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

test('fresh repository reproduces the complete plugin-independent harness contract', {
  skip: process.env.HARNESS_FRESH_CHILD === '1' && 'outer proof owns recursive fresh-repository coverage',
}, () => {
  const root = createFreshRepositorySnapshot();
  const childPaths = git(root, 'ls-files', '-z').stdout.split('\0').filter(Boolean);
  assert.deepEqual(childPaths.filter(isPluginOrMcpPath), []);

  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const installed = run(root, process.execPath, ['scripts/harness/install-hooks.mjs']);
    assert.equal(installed.status, 0, `installer attempt ${attempt}: ${installed.stderr || installed.stdout}`);
  }

  const tests = run(root, process.execPath, ['--test', ...childHarnessTests(root)], {
    env: { ...process.env, HARNESS_FRESH_CHILD: '1' },
  });
  assert.equal(tests.status, 0, tests.stderr || tests.stdout);

  const selfTest = run(root, process.execPath, ['scripts/harness/guard.mjs', '--self-test']);
  assert.equal(selfTest.status, 0, selfTest.stderr || selfTest.stdout);

  const audit = run(root, process.execPath, ['scripts/harness/harness-audit.mjs']);
  assert.equal(audit.status, 0, audit.stderr || audit.stdout);
  assert.match(audit.stdout, /harness-audit: healthy/i);

  const manifest = JSON.parse(readFileSync(join(root, 'scripts/harness/manifest.json'), 'utf8'));
  const tracked = git(root, 'ls-files', '--error-unmatch', '--', ...manifest.requiredTrackedFiles);
  assert.equal(tracked.status, 0, tracked.stderr || tracked.stdout);
  assert.equal(git(root, 'diff', '--cached', '--exit-code').status, 0);
  const clean = git(root, 'diff', '--exit-code');
  assert.equal(clean.status, 0, clean.stderr || clean.stdout);
  assert.equal(git(root, 'status', '--porcelain=v1').stdout, '');

  const untrackedRequired = manifest.requiredTrackedFiles.at(-1);
  assert.equal(git(root, 'rm', '--cached', '--', untrackedRequired).status, 0);
  const untrackedAudit = run(root, process.execPath, ['scripts/harness/harness-audit.mjs']);
  assert.notEqual(untrackedAudit.status, 0);
  assert.match(untrackedAudit.stdout, new RegExp(`${untrackedRequired.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}.*not tracked`, 'i'));
  assert.equal(git(root, 'reset', '--hard', 'HEAD').status, 0);

  put(root, 'scripts/harness/deleted-reference.mjs', 'export {};\n');
  put(root, '.claude/commands/deleted-reference-proof.md', 'node scripts/harness/deleted-reference.mjs\n');
  assert.equal(git(root, 'add', '--all').status, 0);
  assert.equal(git(root, 'commit', '-m', 'add referenced harness command').status, 0);
  assert.equal(git(root, 'rm', 'scripts/harness/deleted-reference.mjs').status, 0);
  const deletedReferenceAudit = run(root, process.execPath, ['scripts/harness/harness-audit.mjs']);
  assert.notEqual(deletedReferenceAudit.status, 0);
  assert.match(deletedReferenceAudit.stdout, /broken reference.*deleted-reference\.mjs.*not tracked/i);
});
