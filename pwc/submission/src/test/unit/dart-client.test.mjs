import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeSingleEntryZip } from './helpers/zip.mjs';

// 모듈 로드 시점에 API_KEY 가 고정되므로 import 전에 env 를 심는다 (네트워크 0 보장)
process.env.DART_API_KEY = 'test-key';
const client = await import('../../dart/client.mjs');

let lastUrl = '';
function stubFetch(responder) {
  globalThis.fetch = async (url) => {
    lastUrl = String(url);
    return responder(lastUrl);
  };
}
const jsonRes = (body, status = 200) => new Response(JSON.stringify(body), { status });

test('API_KEY — env 에서 로드', () => {
  assert.equal(client.API_KEY, 'test-key');
  client.requireKey(); // throw 하지 않아야 한다
});

test('company — crtfc_key/corp_code 쿼리와 정상(000) 응답', async () => {
  stubFetch(() => jsonRes({ status: '000', corp_name: '테스트기업' }));
  const body = await client.company('00126380');
  assert.equal(body.corp_name, '테스트기업');
  assert.match(lastUrl, /company\.json\?/);
  assert.match(lastUrl, /crtfc_key=test-key/);
  assert.match(lastUrl, /corp_code=00126380/);
});

test('getJson — status 013(데이터 없음)은 에러가 아니다', async () => {
  stubFetch(() => jsonRes({ status: '013', message: '조회된 데이타가 없습니다' }));
  const body = await client.company('00126380');
  assert.equal(body.status, '013');
});

test('getJson — status 100 은 throw', async () => {
  stubFetch(() => jsonRes({ status: '100', message: '잘못된 키' }));
  await assert.rejects(() => client.company('00126380'), /status 100/);
});

test('getJson — HTTP 오류는 throw', async () => {
  stubFetch(() => jsonRes({}, 500));
  await assert.rejects(() => client.company('00126380'), /HTTP 500/);
});

test('disclosures — 선택 파라미터(corpCode/pblntfTy) 유무에 따른 쿼리', async () => {
  stubFetch(() => jsonRes({ status: '000', list: [] }));
  await client.disclosures({ corpCode: '00126380', bgnDe: '20260101', endDe: '20260701', pblntfTy: 'A' });
  assert.match(lastUrl, /list\.json/);
  assert.match(lastUrl, /corp_code=00126380/);
  assert.match(lastUrl, /pblntf_ty=A/);
  assert.match(lastUrl, /page_count=50/);

  await client.disclosures({ bgnDe: '20260101', endDe: '20260701', pageCount: 5, pageNo: 2 });
  assert.doesNotMatch(lastUrl, /corp_code=/);
  assert.doesNotMatch(lastUrl, /pblntf_ty=/);
  assert.match(lastUrl, /page_count=5/);
  assert.match(lastUrl, /page_no=2/);
});

test('financialSummary / financialFull — 기본 보고서·연결 코드', async () => {
  stubFetch(() => jsonRes({ status: '000', list: [] }));
  await client.financialSummary({ corpCode: '00126380', year: 2025 });
  assert.match(lastUrl, /fnlttSinglAcnt\.json/);
  assert.match(lastUrl, /bsns_year=2025/);
  assert.match(lastUrl, /reprt_code=11011/);

  await client.financialFull({ corpCode: '00126380', year: 2025, reprtCode: '11013', fsDiv: 'OFS' });
  assert.match(lastUrl, /fnlttSinglAcntAll\.json/);
  assert.match(lastUrl, /reprt_code=11013/);
  assert.match(lastUrl, /fs_div=OFS/);
});

test('corpCodeZip — zip 매직이면 버퍼 그대로 반환', async () => {
  const zip = makeSingleEntryZip('CORPCODE.xml', '<result/>');
  stubFetch(() => new Response(zip, { status: 200 }));
  const buf = await client.corpCodeZip();
  assert.ok(Buffer.isBuffer(buf));
  assert.equal(buf.readUInt32LE(0), 0x04034b50);
});

test('getBinary — zip 이 아닌 응답(XML 오류 본문)은 throw', async () => {
  stubFetch(() => new Response('<err>bad key</err>', { status: 200 }));
  await assert.rejects(() => client.corpCodeZip(), /zip 이 아닌 응답/);
});

test('getBinary — 4바이트 미만 응답도 throw', async () => {
  stubFetch(() => new Response(Buffer.from([1, 2]), { status: 200 }));
  await assert.rejects(() => client.corpCodeZip(), /zip 이 아닌 응답/);
});
