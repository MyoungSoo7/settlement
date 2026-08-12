import assert from 'node:assert/strict';
import {
  chmodSync,
  cpSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
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
  // copilot 플러그인 트리는 서비스 소유 기준으로 재배치될 수 있으므로 부모 경로를 고정하지 않는다
  // (settlement-copilot → settlement-service/src/main/resources/. 소유 서비스가 없는 제출물은 저장소 미포함.)
  //
  // 로스터는 CLAUDE.md 의 배치 기준이 정본이다 — settlement-copilot·fashion-copilot·pwc(trusted-ceo)
  // 는 서비스 resources 아래, invest-copilot 은 docs/harness/hackathon 아래. 하나라도 빠뜨리면
  // fresh-repo 증명이 "플러그인 없이 선다"를 못 보이고, 남은 플러그인 문서가 걷어낸 플러그인을
  // 가리켜 링크가 끊긴다(실사고: fashion-copilot → settlement-copilot).
  return /(^|\/)(?:\.claude-plugin|\.codex-plugin|mcp)(?:\/|$)|(^|\/)\.mcp\.json$|(?:^|\/)(?:settlement|invest|fashion)-copilot(?:\/|$)|(?:^|\/)pwc(?:\/|$)/.test(
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

// findGitRoot 는 실경로를 돌려준다. macOS tmpdir() 은 /var → /private/var 심링크라
// 픽스처 쪽도 실경로로 정규화해 두지 않으면 개발자 맥에서만 깨진다(리눅스 CI 는 통과).
function makeTempRoot(prefix) {
  return realpathSync(mkdtempSync(join(tmpdir(), prefix)));
}

function createRepo() {
  const root = makeTempRoot('harness-install-');
  temporaryRoots.push(root);
  assert.equal(git(root, 'init').status, 0);
  assert.equal(git(root, 'config', 'user.name', 'Harness Test').status, 0);
  assert.equal(git(root, 'config', 'user.email', 'harness@example.test').status, 0);
  cpSync(join(projectRoot, 'scripts/harness/guard.mjs'), join(root, 'scripts/harness/guard.mjs'), { recursive: true });
  cpSync(join(projectRoot, 'scripts/harness/telemetry.mjs'), join(root, 'scripts/harness/telemetry.mjs'), { recursive: true });
  cpSync(join(projectRoot, 'scripts/harness/hooks/pre-commit'), join(root, 'scripts/harness/hooks/pre-commit'), { recursive: true });
  if (process.platform !== 'win32') chmodSync(join(root, 'scripts/harness/hooks/pre-commit'), 0o755);
  return root;
}

function createFreshRepositorySnapshot() {
  const root = makeTempRoot('harness-fresh-repository-');
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
  put(root, 'settlement-service/src/main/resources/settlement-copilot/hooks/guards/pre-commit.mjs', "console.error('plugin rejected commit'); process.exit(23);\n");
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

// fresh-repo 증명은 "플러그인 없이도 하네스가 선다"를 보이는 것이므로, 플러그인 트리를 하나라도
// 남기면 증명이 약해진다. 실제로 fashion-copilot 이 남아 settlement-copilot 링크가 끊겼다
// (필터가 settlement/invest 만 알고 fashion·pwc 를 몰랐다). 로스터는 CLAUDE.md 배치 기준이 정본.
test('plugin filter strips every tracked plugin tree, not just some', () => {
  for (const path of [
    'settlement-service/src/main/resources/settlement-copilot/README.md',
    'order-service/src/main/resources/fashion-copilot/README.md',
    'company-service/src/main/resources/pwc/README.md',
    'docs/harness/hackathon/invest-copilot/README.md',
  ]) {
    assert.ok(isPluginOrMcpPath(path), `플러그인 경로인데 걸러지지 않음: ${path}`);
  }

  for (const path of [
    'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Settlement.java',
    'order-service/src/main/resources/templates/mail.html',
    'scripts/harness/guard.mjs',
    'docs/polyglot-services.md',
  ]) {
    assert.ok(!isPluginOrMcpPath(path), `플러그인이 아닌데 걸러짐: ${path}`);
  }
});
