import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { makeSingleEntryZip, makeZipWithoutEocd, makeCorpXml, ZIP_METHOD_STORED } from './helpers/zip.mjs';

// import 전에 캐시 경로를 임시 파일로 돌려 실캐시(data/cache)를 오염시키지 않는다
const workDir = mkdtempSync(join(tmpdir(), 'tca-corp-'));
const cachePath = join(workDir, 'corp-codes.json');
process.env.CORP_CODES_CACHE = cachePath;
process.env.DART_API_KEY = 'test-key';
const { loadCorpCodes, searchCorp } = await import('../../dart/corp-codes.mjs');

const FIXTURE_COMPANIES = [
  { corpCode: '00126380', name: '삼성전자', stockCode: '005930', modifyDate: '20260101' },
  { corpCode: '00126381', name: '삼성전자서비스', stockCode: '005935', modifyDate: '20260101' },
  { corpCode: '00999999', name: '한화', stockCode: '000880', modifyDate: '20260101' },
];

function writeFreshCache() {
  writeFileSync(cachePath, JSON.stringify({
    fetchedAt: new Date().toISOString(),
    totalCount: 3,
    listedCount: 3,
    companies: FIXTURE_COMPANIES,
  }));
}

const denyFetch = () => {
  globalThis.fetch = async () => { throw new Error('네트워크 호출 금지 — 캐시를 써야 한다'); };
};

test.after(() => rmSync(workDir, { recursive: true, force: true }));

test('loadCorpCodes — 신선한 캐시는 네트워크 없이 반환', async () => {
  writeFreshCache();
  denyFetch();
  const data = await loadCorpCodes();
  assert.equal(data.listedCount, 3);
  assert.equal(data.companies.length, 3);
});

test('searchCorp — 고유번호(8자리)/종목코드(6자리) 정확 매칭', async () => {
  writeFreshCache();
  denyFetch();
  const byCorp = await searchCorp('00126381');
  assert.deepEqual(byCorp.matches.map((c) => c.name), ['삼성전자서비스']);
  const byStock = await searchCorp('005930');
  assert.deepEqual(byStock.matches.map((c) => c.name), ['삼성전자']);
});

test('searchCorp — 이름 검색은 정확>접두>포함, 짧은 이름 우선 + limit', async () => {
  writeFreshCache();
  denyFetch();
  const r = await searchCorp('삼성전자');
  assert.deepEqual(r.matches.map((c) => c.name), ['삼성전자', '삼성전자서비스']);
  const limited = await searchCorp('삼성전자', { limit: 1 });
  assert.equal(limited.matches.length, 1);
  const none = await searchCorp('없는회사');
  assert.equal(none.matches.length, 0);
});

test('loadCorpCodes — 오래된 캐시는 재다운로드 후 상장사만 캐시에 기록', async () => {
  writeFileSync(cachePath, JSON.stringify({ fetchedAt: '2020-01-01T00:00:00Z', companies: [] }));
  const xml = makeCorpXml([
    { corpCode: '00000001', name: '상장사A', stockCode: '111111' },
    { corpCode: '00000002', name: '상장사B', stockCode: '222222' },
    { corpCode: '00000003', name: '비상장사', stockCode: ' ' },
  ]);
  globalThis.fetch = async () => new Response(makeSingleEntryZip('CORPCODE.xml', xml));
  const data = await loadCorpCodes();
  assert.equal(data.totalCount, 3);
  assert.equal(data.listedCount, 2);
  assert.deepEqual(data.companies.map((c) => c.name), ['상장사A', '상장사B']);
  const persisted = JSON.parse(readFileSync(cachePath, 'utf8'));
  assert.equal(persisted.listedCount, 2);
});

test('loadCorpCodes — refresh:true 는 신선한 캐시도 무시, listedOnly:false 는 전체 반환', async () => {
  writeFreshCache();
  const xml = makeCorpXml([
    { corpCode: '00000001', name: '상장사A', stockCode: '111111' },
    { corpCode: '00000003', name: '비상장사', stockCode: ' ' },
  ]);
  globalThis.fetch = async () => new Response(makeSingleEntryZip('CORPCODE.xml', xml));
  const data = await loadCorpCodes({ refresh: true, listedOnly: false });
  assert.equal(data.companies.length, 2);
  assert.equal(data.companies[1].stockCode, '');
});

test('unzipSingle — STORED(무압축) 방식도 해제', async () => {
  rmSync(cachePath, { force: true });
  assert.equal(existsSync(cachePath), false);
  const xml = makeCorpXml([{ corpCode: '00000009', name: '스토어드', stockCode: '999999' }]);
  globalThis.fetch = async () => new Response(makeSingleEntryZip('CORPCODE.xml', xml, { method: ZIP_METHOD_STORED }));
  const data = await loadCorpCodes();
  assert.deepEqual(data.companies.map((c) => c.name), ['스토어드']);
});

test('unzipSingle — 지원하지 않는 압축 방식은 throw', async () => {
  rmSync(cachePath, { force: true });
  globalThis.fetch = async () => new Response(makeSingleEntryZip('CORPCODE.xml', '<result/>', { method: 99 }));
  await assert.rejects(() => loadCorpCodes(), /지원하지 않는 zip 압축 방식: 99/);
});

test('unzipSingle — EOCD 없는 zip 은 throw', async () => {
  rmSync(cachePath, { force: true });
  globalThis.fetch = async () => new Response(makeZipWithoutEocd());
  await assert.rejects(() => loadCorpCodes(), /EOCD/);
});
