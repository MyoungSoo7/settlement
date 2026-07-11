import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { findEnvKey, safeErrorMessage } from '../../common/env.mjs';

test('safeErrorMessage — Error / string / message객체 / 기타', () => {
  assert.equal(safeErrorMessage(new Error('boom')), 'boom');
  assert.equal(safeErrorMessage('plain'), 'plain');
  assert.equal(safeErrorMessage({ message: 'obj-msg' }), 'obj-msg');
  assert.equal(safeErrorMessage({ message: 123 }), 'Unknown error');
  assert.equal(safeErrorMessage(null), 'Unknown error');
  assert.equal(safeErrorMessage(42), 'Unknown error');
});

test('findEnvKey — process.env 우선', () => {
  process.env.TCA_TEST_KEY = 'from-env';
  try {
    assert.equal(findEnvKey('TCA_TEST_KEY', tmpdir(), 1), 'from-env');
  } finally {
    delete process.env.TCA_TEST_KEY;
  }
});

test('findEnvKey — .env 폴백 (시작 디렉터리, 주석 절단, trim)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-env-'));
  try {
    writeFileSync(join(dir, '.env'), 'OTHER=x\nTCA_FALLBACK_KEY=secret-value # comment\n');
    assert.equal(findEnvKey('TCA_FALLBACK_KEY', dir, 1), 'secret-value');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('findEnvKey — 상위 디렉터리 .env 탐색', () => {
  const parent = mkdtempSync(join(tmpdir(), 'tca-envp-'));
  const child = join(parent, 'child');
  mkdirSync(child);
  try {
    writeFileSync(join(parent, '.env'), 'TCA_PARENT_KEY=up-one\n');
    assert.equal(findEnvKey('TCA_PARENT_KEY', child, 2), 'up-one');
    // depth 1 이면 child 만 보므로 못 찾는다
    assert.equal(findEnvKey('TCA_PARENT_KEY', child, 1), '');
  } finally {
    rmSync(parent, { recursive: true, force: true });
  }
});

test('findEnvKey — 어디에도 없으면 빈 문자열', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-envn-'));
  try {
    writeFileSync(join(dir, '.env'), 'UNRELATED=1\n');
    assert.equal(findEnvKey('TCA_MISSING_KEY', dir, 1), '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
