import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { decideHookOutput, routeSkills, runRouterCli } from '../skill-router.mjs';
import { appendJsonl, guardHitRecords } from '../telemetry.mjs';
import { runGuardCli } from '../guard.mjs';
import { readJsonl, summarize } from '../telemetry-report.mjs';

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), 'skill-router-test-'));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('routeSkills', () => {
  test('settlement ledger source routes ledger-invariants first', () => {
    assert.deepEqual(
      routeSkills('settlement-service/src/main/java/github/lms/lemuel/settlement/ledger/domain/LedgerEntry.java'),
      ['ledger-invariants', 'settlement-domain-rules', 'tdd-discipline'],
    );
  });
  test('service sources route to their *-rules skill plus tdd-discipline last', () => {
    assert.deepEqual(routeSkills('order-service/src/main/java/github/lms/lemuel/order/domain/Order.java'), ['order-commerce-rules', 'tdd-discipline']);
    assert.deepEqual(routeSkills('account-service/src/main/java/github/lms/lemuel/account/domain/JournalEntry.java'), ['account-domain-rules', 'ledger-invariants', 'tdd-discipline']);
  });
  test('kafka consumer path adds idempotency-and-events on top of the service rules', () => {
    assert.deepEqual(
      routeSkills('settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/OrderEventConsumer.java'),
      ['settlement-domain-rules', 'idempotency-and-events', 'tdd-discipline'],
    );
  });
  test('test sources get the tdd-discipline procedure reminder', () => {
    assert.deepEqual(
      routeSkills('settlement-service/src/test/java/github/lms/lemuel/payout/application/service/PayoutServiceTest.java'),
      ['settlement-domain-rules', 'tdd-discipline'],
    );
  });
  test('suggestions are capped at 3 even when more routes match', () => {
    const skills = routeSkills('settlement-service/src/main/java/github/lms/lemuel/settlement/ledger/adapter/in/kafka/LedgerEventConsumer.java');
    assert.equal(skills.length, 3);
    assert.deepEqual(skills, ['ledger-invariants', 'settlement-domain-rules', 'idempotency-and-events']);
  });
  test('event contract fixtures route regardless of extension', () => {
    assert.deepEqual(routeSkills('shared-common/src/testFixtures/resources/contracts/events/order.created.schema.json'), ['event-contract-change']);
  });
  test('non-source files stay silent; unmapped-service sources still get tdd-discipline', () => {
    assert.deepEqual(routeSkills('settlement-service/README.md'), []);
    assert.deepEqual(routeSkills('gateway-service/src/main/java/App.java'), ['tdd-discipline']);
    assert.deepEqual(routeSkills(undefined), []);
  });
});

describe('decideHookOutput', () => {
  const writeEvent = (sessionId) => ({
    session_id: sessionId,
    tool_name: 'Edit',
    tool_input: { file_path: 'loan-service/src/main/java/github/lms/lemuel/loan/domain/LoanAdvance.java' },
  });

  test('suggests once per session, then dedupes; new session suggests again', async () => {
    const repoRoot = await temporaryRepo();
    const first = await decideHookOutput(writeEvent('session-a'), { repoRoot });
    assert.ok(first, 'first edit must produce a suggestion');
    const parsed = JSON.parse(first);
    assert.equal(parsed.hookSpecificOutput.hookEventName, 'PreToolUse');
    assert.match(parsed.hookSpecificOutput.additionalContext, /loan-domain-rules/);
    assert.equal(await decideHookOutput(writeEvent('session-a'), { repoRoot }), null);
    assert.ok(await decideHookOutput(writeEvent('session-b'), { repoRoot }));
    const suggestions = readJsonl(join(repoRoot, '.omc', 'logs', 'skill-suggestions.jsonl'));
    // 세션당 (loan-domain-rules + tdd-discipline) 2건 × 2세션
    assert.equal(suggestions.length, 4);
  });

  test('Skill invocations are logged as usage, not suggestions', async () => {
    const repoRoot = await temporaryRepo();
    const output = await decideHookOutput(
      { session_id: 's', tool_name: 'Skill', tool_input: { skill: 'settlement-domain-rules' } },
      { repoRoot },
    );
    assert.equal(output, null);
    const usage = readJsonl(join(repoRoot, '.omc', 'logs', 'skill-usage.jsonl'));
    assert.equal(usage.length, 1);
    assert.equal(usage[0].skill, 'settlement-domain-rules');
  });

  test('malformed events and CLI misuse never block (exit 0)', async () => {
    const repoRoot = await temporaryRepo();
    assert.equal(await decideHookOutput(null, { repoRoot }), null);
    assert.equal(await runRouterCli(['--hook'], { repoRoot, stdin: 'not json', stderr: () => {} }), 0);
    assert.equal(await runRouterCli(['--bogus'], { repoRoot, stderr: () => {} }), 0);
  });
});

describe('telemetry', () => {
  test('guardHitRecords carries rule id, file, line and mode', () => {
    const now = new Date('2026-07-18T00:00:00Z');
    const records = guardHitRecords('hook', [{ id: 'MONEY-PRIMITIVE', file: 'a.java', line: 3, msg: 'x' }], now);
    assert.deepEqual(records, [{ ts: '2026-07-18T00:00:00.000Z', mode: 'hook', id: 'MONEY-PRIMITIVE', file: 'a.java', line: 3 }]);
  });

  test('HARNESS_TELEMETRY=off disables writes', async () => {
    const repoRoot = await temporaryRepo();
    const written = await appendJsonl(repoRoot, 'guard-hits.jsonl', { a: 1 }, { env: { HARNESS_TELEMETRY: 'off' } });
    assert.equal(written, false);
    assert.equal(existsSync(join(repoRoot, '.omc', 'logs', 'guard-hits.jsonl')), false);
  });

  test('guard hook mode records blocked violations to guard-hits.jsonl', async () => {
    const repoRoot = await temporaryRepo();
    const file = 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java';
    await mkdir(join(repoRoot, ...file.split('/').slice(0, -1)), { recursive: true });
    await writeFile(join(repoRoot, ...file.split('/')), 'placeholder\n', 'utf8');
    const event = { tool_name: 'Write', tool_input: { file_path: file, content: 'double amount = 1.0;\n' } };
    const exitCode = await runGuardCli(['--hook'], { repoRoot, stdin: JSON.stringify(event), stdout: () => {}, stderr: () => {} });
    assert.equal(exitCode, 2);
    const hits = readJsonl(join(repoRoot, '.omc', 'logs', 'guard-hits.jsonl'));
    assert.equal(hits.length, 1);
    assert.equal(hits[0].id, 'MONEY-PRIMITIVE');
    assert.equal(hits[0].mode, 'hook');
  });

  test('summarize surfaces zero-fire rules and ignored suggestions', () => {
    const report = summarize({
      hits: [{ ts: '2026-07-18T01:00:00Z', mode: 'hook', id: 'MONEY-PRIMITIVE', file: 'a.java', line: 1 }],
      usage: [{ ts: '2026-07-18T01:00:00Z', skill: 'money-safety' }],
      suggestions: [{ ts: '2026-07-18T01:00:00Z', skill: 'ledger-invariants', file: 'b.java' }],
      now: new Date('2026-07-18T02:00:00Z'),
    });
    assert.match(report, /MONEY-PRIMITIVE/);
    assert.match(report, /IMMUTABLE-HISTORY.*0회/);
    assert.match(report, /ledger-invariants/);
  });
});
