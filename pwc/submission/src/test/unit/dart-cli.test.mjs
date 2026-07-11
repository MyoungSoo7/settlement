import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CLI = join(HERE, '..', '..', 'bin', 'dart-cli.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

const workDir = mkdtempSync(join(tmpdir(), 'tca-cli-'));
const cachePath = join(workDir, 'cache.json');
const stubFile = join(workDir, 'stub.json');
writeFileSync(cachePath, JSON.stringify({
  fetchedAt: new Date().toISOString(),
  totalCount: 1, listedCount: 1,
  companies: [{ corpCode: '00126380', name: '삼성전자', stockCode: '005930', modifyDate: '20260101' }],
}));
writeFileSync(stubFile, JSON.stringify({
  rules: [
    { match: 'corp_code=99999999', json: { status: '100', message: '잘못된 키' } },
    { match: 'company.json', json: { status: '000', corp_name: '삼성전자' } },
    { match: 'list.json', json: { status: '000', list: [] } },
    { match: 'fnlttSinglAcnt.json', json: { status: '000', list: [] } },
  ],
}));
const ENV = withFetchStub(PRELOAD, stubFile, { DART_API_KEY: 'test-key', CORP_CODES_CACHE: cachePath });

test.after(() => rmSync(workDir, { recursive: true, force: true }));

test('dart-cli — 명령 없으면 usage + exit 2', () => {
  const r = runNode([CLI], ENV);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /usage: dart-cli/);
});

test('dart-cli search — 캐시 픽스처에서 검색', () => {
  const r = runNode([CLI, 'search', '삼성전자'], ENV);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(JSON.parse(r.stdout).matches[0].corpCode, '00126380');
});

test('dart-cli company / disclosures / fin — 스텁 fetch 왕복', () => {
  const company = runNode([CLI, 'company', '00126380'], ENV);
  assert.equal(company.status, 0, company.stderr);
  assert.equal(JSON.parse(company.stdout).corp_name, '삼성전자');

  const disclosures = runNode([CLI, 'disclosures', '00126380', '7'], ENV);
  assert.equal(disclosures.status, 0, disclosures.stderr);
  assert.equal(JSON.parse(disclosures.stdout).status, '000');

  const fin = runNode([CLI, 'fin', '00126380', '2025'], ENV);
  assert.equal(fin.status, 0, fin.stderr);
  assert.equal(JSON.parse(fin.stdout).status, '000');
});

test('dart-cli — DART 오류는 ERROR + exit 1', () => {
  const r = runNode([CLI, 'company', '99999999'], ENV);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /ERROR: .*status 100/);
});
