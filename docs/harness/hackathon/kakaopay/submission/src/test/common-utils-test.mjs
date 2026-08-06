#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { findEnvKey, safeErrorMessage } from '../common/env.mjs';
import { parseJsonRpcLines } from './smoke-harness.mjs';

const originalValue = process.env.UNIT_TEST_API_KEY;
delete process.env.UNIT_TEST_API_KEY;

const dir = mkdtempSync(join(tmpdir(), 'trusted-ceo-agent-'));
const child = join(dir, 'a', 'b');
await import('node:fs/promises').then(({ mkdir }) => mkdir(child, { recursive: true }));
writeFileSync(join(dir, '.env'), 'UNIT_TEST_API_KEY=file-secret\n');

assert.equal(findEnvKey('UNIT_TEST_API_KEY', child, 4), 'file-secret');

process.env.UNIT_TEST_API_KEY = 'env-secret';
assert.equal(findEnvKey('UNIT_TEST_API_KEY', child, 4), 'env-secret');

assert.equal(safeErrorMessage(new Error('boom')), 'boom');
assert.equal(safeErrorMessage('plain'), 'plain');
assert.equal(safeErrorMessage({ message: 'object-message' }), 'object-message');
assert.equal(safeErrorMessage({}), 'Unknown error');

const parsed = parseJsonRpcLines('{"id":1}\n\n{"id":2}\n');
assert.deepEqual(parsed.messages, [{ id: 1 }, { id: 2 }]);
assert.equal(parsed.remainder, '');

if (originalValue === undefined) {
  delete process.env.UNIT_TEST_API_KEY;
} else {
  process.env.UNIT_TEST_API_KEY = originalValue;
}
rmSync(dir, { recursive: true, force: true });

console.log('ALL GREEN');
