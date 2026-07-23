import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { canaryResults, runReportCli } from '../telemetry-report.mjs';
import { LOG_DIR_SEGMENTS } from '../telemetry.mjs';

const NOW = new Date('2026-07-22T09:00:00Z');

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), 'telemetry-report-test-'));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function seedLog(root, fileName, records) {
  const directory = join(root, ...LOG_DIR_SEGMENTS);
  await mkdir(directory, { recursive: true });
  await writeFile(join(directory, fileName), records.map((record) => JSON.stringify(record)).join('\n') + '\n', 'utf8');
}

describe('guard canaries', () => {
  test('every rule has a canary fixture and every canary passes', () => {
    const results = canaryResults();
    assert.ok(results.length > 0);
    for (const { id, status } of results) {
      assert.equal(status, 'pass', `canary for ${id} must pass (got ${status})`);
    }
  });
});

describe('SessionStart hook mode (--hook)', () => {
  test('emits SessionStart additionalContext when telemetry data exists', async () => {
    const root = await temporaryRepo();
    await seedLog(root, 'guard-hits.jsonl', [
      { ts: '2026-07-21T10:00:00Z', mode: 'hook', id: 'MONEY-PRIMITIVE', file: 'x.java', line: 1 },
      { ts: '2026-07-21T11:00:00Z', mode: 'hook', id: 'MONEY-PRIMITIVE', file: 'y.java', line: 2 },
    ]);
    await seedLog(root, 'skill-suggestions.jsonl', [
      { ts: '2026-07-21T10:00:00Z', skill: 'money-safety', file: 'x.java' },
    ]);
    const output = [];
    assert.equal(await runReportCli(['--hook', '--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    const parsed = JSON.parse(output.join(''));
    assert.equal(parsed.hookSpecificOutput.hookEventName, 'SessionStart');
    assert.match(parsed.hookSpecificOutput.additionalContext, /가드 차단/);
    assert.match(parsed.hookSpecificOutput.additionalContext, /MONEY-PRIMITIVE/);
    assert.match(parsed.hookSpecificOutput.additionalContext, /순응률/);
  });

  test('stays silent with no data and healthy canaries, always exit 0', async () => {
    const root = await temporaryRepo();
    const output = [];
    assert.equal(await runReportCli(['--hook', '--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    assert.deepEqual(output, []);
  });

  test('malformed jsonl lines never break the hook', async () => {
    const root = await temporaryRepo();
    await seedLog(root, 'guard-hits.jsonl', []);
    const directory = join(root, ...LOG_DIR_SEGMENTS);
    await writeFile(join(directory, 'guard-hits.jsonl'), '{not json}\n{"ts":"2026-07-21T10:00:00Z","mode":"hook","id":"MSA-BOUNDARY","file":"z.java","line":3}\n', 'utf8');
    const output = [];
    assert.equal(await runReportCli(['--hook', '--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    assert.match(JSON.parse(output.join('')).hookSpecificOutput.additionalContext, /MSA-BOUNDARY/);
  });
});
