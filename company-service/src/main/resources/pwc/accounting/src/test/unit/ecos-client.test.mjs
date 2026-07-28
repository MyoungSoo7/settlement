import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.ECOS_API_KEY = 'test-key';
const ecos = await import('../../ecos/client.mjs');

let lastUrl = '';
function stubFetch(responder) {
  globalThis.fetch = async (url) => {
    lastUrl = String(url);
    return responder(lastUrl);
  };
}
const jsonRes = (body, status = 200) => new Response(JSON.stringify(body), { status });

test('API_KEY/requireKey — env 키 인식', () => {
  assert.equal(ecos.API_KEY, 'test-key');
  ecos.requireKey();
});

test('statisticSearch — 정상 행 파싱, 결측("-"/빈값/NaN) skip', async () => {
  stubFetch(() => jsonRes({
    StatisticSearch: {
      list_total_count: 4,
      row: [
        { TIME: '20260601', DATA_VALUE: '2.5' },
        { TIME: '20260602', DATA_VALUE: '' },
        { TIME: '20260603', DATA_VALUE: '-' },
        { TIME: '20260604', DATA_VALUE: 'abc' },
        { TIME: '20260605', DATA_VALUE: '2.75' },
      ],
    },
  }));
  const rows = await ecos.statisticSearch({ statCode: '722Y001', itemCode: '0101000', cycle: 'D', start: '20260601', end: '20260630' });
  assert.deepEqual(rows, [
    { time: '20260601', value: 2.5 },
    { time: '20260605', value: 2.75 },
  ]);
  assert.match(lastUrl, /StatisticSearch\/test-key\/json\/kr\/1\/10000\/722Y001\/D\/20260601\/20260630\/0101000/);
});

test('statisticSearch — INFO-200(데이터 없음)은 빈 배열', async () => {
  stubFetch(() => jsonRes({ RESULT: { CODE: 'INFO-200', MESSAGE: '해당하는 데이터가 없습니다' } }));
  assert.deepEqual(await ecos.statisticSearch({ statCode: 'X', itemCode: 'Y', cycle: 'D', start: '1', end: '2' }), []);
});

test('statisticSearch — 중첩 RESULT 오류 코드는 throw', async () => {
  stubFetch(() => jsonRes({ StatisticSearch: { RESULT: { CODE: 'INFO-100', MESSAGE: '인증키 오류' } } }));
  await assert.rejects(
    () => ecos.statisticSearch({ statCode: '722Y001', itemCode: 'Y', cycle: 'D', start: '1', end: '2' }),
    /INFO-100.*722Y001/,
  );
});

test('statisticSearch — HTTP 오류는 throw / row 부재는 빈 배열', async () => {
  stubFetch(() => jsonRes({}, 503));
  await assert.rejects(() => ecos.statisticSearch({ statCode: 'X', itemCode: 'Y', cycle: 'D', start: '1', end: '2' }), /HTTP 503/);
  stubFetch(() => jsonRes({ StatisticSearch: { list_total_count: 0 } }));
  assert.deepEqual(await ecos.statisticSearch({ statCode: 'X', itemCode: 'Y', cycle: 'D', start: '1', end: '2' }), []);
});

test('fetchIndicator — D 주기(yyyyMMdd) 날짜 포맷 + 변화량 계산', async () => {
  stubFetch(() => jsonRes({
    StatisticSearch: { row: [{ TIME: '20260401', DATA_VALUE: '2.50' }, { TIME: '20260615', DATA_VALUE: '2.75' }] },
  }));
  const r = await ecos.fetchIndicator('BASE_RATE', { monthsBack: 2, now: new Date(2026, 5, 15) });
  assert.match(lastUrl, /722Y001\/D\/20260415\/20260615\/0101000/);
  assert.equal(r.count, 2);
  assert.equal(r.first.value, 2.5);
  assert.equal(r.latest.value, 2.75);
  assert.equal(r.changeFromFirst, 0.25);
  assert.equal(r.name, '한국은행 기준금리');
});

test('fetchIndicator — M 주기(yyyyMM) 포맷 (CPI)', async () => {
  stubFetch(() => jsonRes({ StatisticSearch: { row: [] } }));
  const r = await ecos.fetchIndicator('CPI', { monthsBack: 13, now: new Date(2026, 5, 15) });
  assert.match(lastUrl, /901Y009\/M\/202505\/202606\/0/);
  assert.equal(r.count, 0);
  assert.equal(r.first, null);
  assert.equal(r.latest, null);
  assert.equal(r.changeFromFirst, null);
});

test('fetchIndicator — 알 수 없는 지표 코드는 throw', async () => {
  await assert.rejects(() => ecos.fetchIndicator('NOPE'), /알 수 없는 지표: NOPE/);
});

test('keyStatistics — 100대 지표 매핑 (결측 DATA_VALUE 는 null)', async () => {
  stubFetch(() => jsonRes({
    KeyStatisticList: {
      row: [
        { CLASS_NAME: '금리', KEYSTAT_NAME: '기준금리', DATA_VALUE: '2.5', UNIT_NAME: '%', CYCLE: '20260601' },
        { CLASS_NAME: '물가', KEYSTAT_NAME: 'CPI', DATA_VALUE: '', UNIT_NAME: '', CYCLE: '' },
        {},
      ],
    },
  }));
  const rows = await ecos.keyStatistics();
  assert.match(lastUrl, /KeyStatisticList\/test-key\/json\/kr\/1\/100/);
  assert.equal(rows.length, 3);
  assert.deepEqual(rows[0], { class: '금리', name: '기준금리', value: 2.5, unit: '%', time: '20260601' });
  assert.equal(rows[1].value, null);
  assert.equal(rows[2].value, null);
});

test('keyStatistics — RESULT 오류는 throw, INFO-200 은 빈 배열', async () => {
  stubFetch(() => jsonRes({ RESULT: { CODE: 'ERR-300', MESSAGE: '점검중' } }));
  await assert.rejects(() => ecos.keyStatistics(), /ERR-300/);
  stubFetch(() => jsonRes({ RESULT: { CODE: 'INFO-200', MESSAGE: '없음' } }));
  assert.deepEqual(await ecos.keyStatistics(), []);
});

test('INDICATORS — 카탈로그 4종 좌표 고정 (economics-service V1 시드와 동일)', () => {
  assert.deepEqual(Object.keys(ecos.INDICATORS), ['BASE_RATE', 'TREASURY_3Y', 'USD_KRW', 'CPI']);
  assert.equal(ecos.INDICATORS.BASE_RATE.statCode, '722Y001');
  assert.equal(ecos.INDICATORS.CPI.cycle, 'M');
});
