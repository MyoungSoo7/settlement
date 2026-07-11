import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CLI = join(HERE, '..', '..', 'bin', 'dart-to-csv.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

const workDir = mkdtempSync(join(tmpdir(), 'tca-dart-csv-'));
const outDir = join(workDir, 'out');
const stubFile = join(workDir, 'stub.json');

function account({ reprtCode, year, fsDiv = 'CFS', ord, account, amount, prev = '0', period = '2026.01.01 ~ 2026.03.31' }) {
  return {
    rcept_no: '20260515000000',
    reprt_code: reprtCode,
    bsns_year: String(year),
    corp_code: '00164779',
    stock_code: '000660',
    fs_div: fsDiv,
    fs_nm: fsDiv === 'CFS' ? '연결재무제표' : '재무제표',
    sj_div: ['1', '5', '11', '21'].includes(String(ord)) ? 'BS' : 'IS',
    account_nm: account,
    thstrm_dt: period,
    thstrm_amount: amount,
    frmtrm_amount: prev,
    ord: String(ord),
    currency: 'KRW',
  };
}

writeFileSync(stubFile, JSON.stringify({
  rules: [
    { match: 'company.json', json: { status: '000', corp_name: '에스케이하이닉스(주)', stock_code: '000660' } },
    { match: 'bsns_year=2026&reprt_code=11013', json: { status: '000', list: [
      account({ reprtCode: '11013', year: 2026, ord: 5, account: '자산총계', amount: '222,828,744,000,000', period: '2026.03.31 현재' }),
      account({ reprtCode: '11013', year: 2026, ord: 11, account: '부채총계', amount: '58,448,945,000,000', period: '2026.03.31 현재' }),
      account({ reprtCode: '11013', year: 2026, ord: 21, account: '자본총계', amount: '164,379,799,000,000', period: '2026.03.31 현재' }),
      account({ reprtCode: '11013', year: 2026, ord: 23, account: '매출액', amount: '52,576,287,000,000' }),
      account({ reprtCode: '11013', year: 2026, ord: 25, account: '영업이익', amount: '37,610,283,000,000' }),
      account({ reprtCode: '11013', year: 2026, ord: 29, account: '당기순이익(손실)', amount: '40,345,909,000,000' }),
    ] } },
    { match: 'bsns_year=2025&reprt_code=11011', json: { status: '000', list: [
      account({ reprtCode: '11011', year: 2025, ord: 5, account: '자산총계', amount: '176,107,659,000,000', period: '2025.12.31 현재' }),
      account({ reprtCode: '11011', year: 2025, ord: 11, account: '부채총계', amount: '55,440,908,000,000', period: '2025.12.31 현재' }),
      account({ reprtCode: '11011', year: 2025, ord: 21, account: '자본총계', amount: '120,666,751,000,000', period: '2025.12.31 현재' }),
      account({ reprtCode: '11011', year: 2025, ord: 23, account: '매출액', amount: '97,146,675,000,000', period: '2025.01.01 ~ 2025.12.31' }),
      account({ reprtCode: '11011', year: 2025, ord: 25, account: '영업이익', amount: '47,206,319,000,000', period: '2025.01.01 ~ 2025.12.31' }),
      account({ reprtCode: '11011', year: 2025, ord: 29, account: '당기순이익(손실)', amount: '42,947,902,000,000', period: '2025.01.01 ~ 2025.12.31' }),
    ] } },
    { match: 'fnlttSinglAcnt.json', json: { status: '013', message: '조회된 데이타가 없습니다' } },
  ],
}));

const ENV = withFetchStub(PRELOAD, stubFile, { DART_API_KEY: 'test-key' });

test.after(() => rmSync(workDir, { recursive: true, force: true }));

test('dart-to-csv — DART 재무요약을 public trial balance CSV로 생성', () => {
  const r = runNode([CLI, '--corp-code', '00164779', '--stock-code', '000660', '--company-name', 'SK하이닉스', '--years', '2025,2026', '--reports', '11011,11013', '--out-dir', outDir], ENV);
  assert.equal(r.status, 0, r.stderr);
  const summary = JSON.parse(r.stdout);
  assert.equal(summary.rows, 2);
  assert.equal(summary.files.length, 1);
  assert.equal(existsSync(summary.files[0]), true);

  const csv = readFileSync(summary.files[0], 'utf8').trim().split(/\r?\n/);
  assert.equal(csv[0], 'period,bsns_year,reprt_code,corp_code,stock_code,company_name,fs_div,sales,operating_income,net_income,total_assets,total_liabilities,total_equity,currency,source');
  assert.equal(csv[1], '2025,2025,11011,00164779,000660,SK하이닉스,CFS,97146675000000,47206319000000,42947902000000,176107659000000,55440908000000,120666751000000,KRW,DART');
  assert.equal(csv[2], '2026Q1,2026,11013,00164779,000660,SK하이닉스,CFS,52576287000000,37610283000000,40345909000000,222828744000000,58448945000000,164379799000000,KRW,DART');
});

test('dart-to-csv — 필수 인자가 없으면 usage + exit 2', () => {
  const r = runNode([CLI], ENV);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /usage: dart-to-csv/);
});
