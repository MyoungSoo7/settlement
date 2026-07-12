import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

import {
  discoverStagedFiles,
  normalizeRepoPath,
  parseAllowance,
  readUtf8Strict,
  reconstructPendingContent,
  runGuardCli,
  scanText,
} from '../guard.mjs';

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), 'guard-test-'));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

const NOW = new Date('2026-07-13T12:00:00Z');
const marker = (fields = '') => ['harness-guard:', 'allow', fields && ` ${fields}`].join('');
const VALID_ALLOWANCE = marker('reason="bounded migration" issue="ISSUE-123" owner="team-settlement" expires="2026-08-01"');

const cases = [
  {
    id: 'MONEY-PRIMITIVE',
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
    violation: 'double amount = 1.0;',
    normal: 'BigDecimal amount = BigDecimal.ONE;',
  },
  {
    id: 'IMMUTABLE-HISTORY',
    file: 'db/migration/V1__ledger.sql',
    violation: 'UPDATE ledger_entries SET amount = 0;',
    normal: 'INSERT INTO ledger_entries (amount) VALUES (0);',
  },
  {
    id: 'MSA-BOUNDARY',
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/App.java',
    violation: 'import github.lms.lemuel.order.domain.Order;',
    normal: 'import github.lms.lemuel.settlement.domain.Settlement;',
  },
  {
    id: 'ACCOUNT-CONSUME-ONLY',
    file: 'account-service/src/main/java/github/lms/lemuel/account/App.java',
    violation: 'kafkaTemplate.send("ledger", event);',
    normal: 'ledgerConsumer.consume(event);',
  },
  {
    id: 'MARKET-NO-VALUATION',
    file: 'market-service/src/main/java/github/lms/lemuel/market/Quote.java',
    violation: 'BigDecimal PBR = price.divide(bookValue);',
    normal: 'BigDecimal marketPrice = quote.price();',
  },
  {
    id: 'NO-COMMIT',
    file: 'pwc/submission/result.json',
    violation: '{}',
    normalFile: 'docs/result.json',
    normal: '{}',
  },
];

describe('guard policy fixtures', () => {
  for (const fixture of cases) {
    test(`${fixture.id} blocks a violation and accepts normal content`, () => {
      const blocked = scanText(fixture.file, fixture.violation, { now: NOW });
      const clean = scanText(fixture.normalFile ?? fixture.file, fixture.normal, { now: NOW });

      assert.deepEqual(blocked.violations.map(({ id }) => id), [fixture.id]);
      assert.deepEqual(clean.violations, []);
    });
  }

  test('ignores Java and Kotlin comment-only lines', () => {
    const java = scanText(cases[0].file, '// double amount = 1.0;', { now: NOW });
    const kotlin = scanText(
      'settlement-service/src/main/kotlin/github/lms/lemuel/settlement/domain/Money.kt',
      '/* float amount = 1.0; */',
      { now: NOW },
    );

    assert.deepEqual(java.violations, []);
    assert.deepEqual(kotlin.violations, []);
  });

  for (const fixture of [
    {
      id: 'MONEY-PRIMITIVE',
      file: String.raw`C:\workspace\repo\settlement-service\src\main\java\github\lms\lemuel\settlement\domain\Money.java`,
      content: 'double amount = 1.0;',
    },
    {
      id: 'MONEY-PRIMITIVE',
      file: '/workspace/repo/settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
      content: 'double amount = 1.0;',
    },
    {
      id: 'ACCOUNT-CONSUME-ONLY',
      file: String.raw`C:\workspace\repo\account-service\src\main\java\github\lms\lemuel\account\Publisher.java`,
      content: 'kafkaTemplate.send("ledger", event);',
    },
    {
      id: 'ACCOUNT-CONSUME-ONLY',
      file: '/workspace/repo/account-service/src/main/java/github/lms/lemuel/account/Publisher.java',
      content: 'kafkaTemplate.send("ledger", event);',
    },
    {
      id: 'NO-COMMIT',
      file: String.raw`C:\workspace\repo\pwc\submission\result.json`,
      content: '{}',
    },
    {
      id: 'NO-COMMIT',
      file: '/workspace/repo/pwc/submission/result.json',
      content: '{}',
    },
  ]) {
    test(`${fixture.id} cannot be bypassed with absolute path ${fixture.file}`, () => {
      const result = scanText(fixture.file, fixture.content, { now: NOW });
      assert.ok(result.violations.some(({ id }) => id === fixture.id));
    });
  }
});

describe('pending content reconstruction', () => {
  test('Write returns the complete pending content', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    assert.equal(await reconstructPendingContent({ tool_name: 'Write', tool_input: { file_path: file, content: 'complete' } }, { repoRoot }), 'complete');
  });

  test('Edit replaces exactly one occurrence in the existing file', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    await writeFile(file, 'before\nold\nafter');
    const event = { tool_name: 'Edit', tool_input: { file_path: file, old_string: 'old', new_string: 'new' } };
    assert.equal(await reconstructPendingContent(event, { repoRoot }), 'before\nnew\nafter');
  });

  test('Edit rejects zero or multiple matches', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    await writeFile(file, 'same same');
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Edit', tool_input: { file_path: file, old_string: 'missing', new_string: 'new' } }, { repoRoot }));
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Edit', tool_input: { file_path: file, old_string: 'same', new_string: 'new' } }, { repoRoot }));
  });

  test('MultiEdit applies edits sequentially and rejects empty edits', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    await writeFile(file, 'one two');
    const event = { tool_name: 'MultiEdit', tool_input: { file_path: file, edits: [
      { old_string: 'one', new_string: 'two' },
      { old_string: 'two two', new_string: 'done' },
    ] } };
    assert.equal(await reconstructPendingContent(event, { repoRoot }), 'done');
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'MultiEdit', tool_input: { file_path: file, edits: [] } }, { repoRoot }));
  });

  test('rejects delete, rename, root paths, and paths outside the repo', async () => {
    const repoRoot = await temporaryRepo();
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Delete', tool_input: { file_path: join(repoRoot, 'x') } }, { repoRoot }));
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Write', tool_input: { file_path: join(repoRoot, 'x'), content: '', new_path: 'y' } }, { repoRoot }));
    await assert.rejects(() => normalizeRepoPath(repoRoot, repoRoot));
    await assert.rejects(() => normalizeRepoPath(repoRoot, join(repoRoot, '..', 'escape')));
  });

  test('rejects a symlink escape through the nearest existing ancestor', async () => {
    const repoRoot = await temporaryRepo();
    const outside = await temporaryRepo();
    const link = join(repoRoot, 'link');
    await symlink(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
    await assert.rejects(() => normalizeRepoPath(repoRoot, join(link, 'new', 'file.txt')));
  });

  test('UTF-8 strict reader rejects invalid bytes', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'invalid.txt');
    await writeFile(file, Buffer.from([0xc3, 0x28]));
    await assert.rejects(() => readUtf8Strict(file));
  });
});

describe('CLI dispatcher', () => {
  test('rejects malformed hook input with exit 2', async () => {
    assert.equal(await runGuardCli(['--hook'], { repoRoot: process.cwd(), stdin: '{', stdout() {}, stderr() {} }), 2);
  });

  test('hook CLI reads a valid event from stdin', async () => {
    const result = spawnSync(process.execPath, ['scripts/harness/guard.mjs', '--hook'], {
      cwd: process.cwd(),
      input: JSON.stringify({
        tool_name: 'Write',
        tool_input: { file_path: 'docs/guard-clean.txt', content: 'clean' },
      }),
    });
    assert.equal(result.status, 0, result.stderr.toString());
  });

  test('rejects missing list with exit 1 and conflicting modes with exit 2', async () => {
    assert.equal(await runGuardCli(['--list'], { repoRoot: process.cwd(), stdout() {}, stderr() {} }), 1);
    assert.equal(await runGuardCli(['--staged', '--files', 'x'], { repoRoot: process.cwd(), stdout() {}, stderr() {} }), 2);
  });

  test('discovers ACMR staged paths including spaces and renames', async () => {
    const repoRoot = await temporaryRepo();
    spawnSync('git', ['init'], { cwd: repoRoot });
    spawnSync('git', ['config', 'user.email', 'guard@example.com'], { cwd: repoRoot });
    spawnSync('git', ['config', 'user.name', 'Guard'], { cwd: repoRoot });
    await writeFile(join(repoRoot, 'old name.txt'), 'old');
    spawnSync('git', ['add', '.'], { cwd: repoRoot });
    spawnSync('git', ['commit', '-m', 'base'], { cwd: repoRoot });
    spawnSync('git', ['mv', 'old name.txt', 'new name.txt'], { cwd: repoRoot });
    assert.deepEqual(discoverStagedFiles(repoRoot), ['new name.txt']);
  });

  test('self-test child process exits 0', () => {
    const result = spawnSync(process.execPath, ['scripts/harness/guard.mjs', '--self-test'], { cwd: process.cwd() });
    assert.equal(result.status, 0, result.stderr?.toString());
  });
});

describe('structured allowances', () => {
  test('parses an auditable allowance', () => {
    assert.deepEqual(parseAllowance(`// ${VALID_ALLOWANCE}`, { now: NOW }), {
      reason: 'bounded migration',
      issue: 'ISSUE-123',
      owner: 'team-settlement',
      expires: '2026-08-01',
    });
  });

  test('accepts a GitHub issue URL', () => {
    const line = marker('reason="temporary bridge" issue="https://github.com/acme/settlement/issues/42" owner="team-platform" expires="2026-08-01"');
    assert.equal(parseAllowance(line, { now: NOW })?.issue, 'https://github.com/acme/settlement/issues/42');
  });

  for (const [name, allowanceText] of [
    ['bare', marker()],
    ['missing field', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement"')],
    ['extra field', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-08-01" ticket="shadow"')],
    ['blank reason', marker('reason="   " issue="ISSUE-1" owner="team-settlement" expires="2026-08-01"')],
    ['malformed issue', marker('reason="bounded" issue="ADR-1" owner="team-settlement" expires="2026-08-01"')],
    ['malformed owner', marker('reason="bounded" issue="ISSUE-1" owner="alice" expires="2026-08-01"')],
    ['invalid date', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-02-30"')],
    ['expired', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-07-12"')],
    ['expires today', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-07-13"')],
  ]) {
    test(`rejects ${name} allowance`, () => {
      assert.equal(parseAllowance(allowanceText, { now: NOW }), null);
      const result = scanText(cases[0].file, `double amount = 1.0; // ${allowanceText}`, { now: NOW });
      assert.ok(result.violations.some(({ id }) => id === 'INVALID-ALLOWANCE'));
      assert.ok(result.violations.some(({ id }) => id === 'MONEY-PRIMITIVE'));
    });
  }

  test('a valid allowance suppresses only its own violating line and is reported', () => {
    const result = scanText(
      cases[0].file,
      `double first = 1.0; // ${VALID_ALLOWANCE}\ndouble second = 2.0;`,
      { now: NOW },
    );

    assert.deepEqual(result.violations.map(({ id, line }) => ({ id, line })), [
      { id: 'MONEY-PRIMITIVE', line: 2 },
    ]);
    assert.deepEqual(result.allowances, [{
      file: cases[0].file,
      line: 1,
      reason: 'bounded migration',
      issue: 'ISSUE-123',
      owner: 'team-settlement',
      expires: '2026-08-01',
    }]);
  });
});
