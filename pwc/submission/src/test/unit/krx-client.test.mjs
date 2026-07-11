import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.KRX_API_KEY = 'test-krx-key';
const krx = await import('../../krx/client.mjs');

let lastUrl = '';
function stubFetch(responder) {
  globalThis.fetch = async (url) => {
    lastUrl = String(url);
    return responder(lastUrl);
  };
}
const jsonRes = (body, status = 200) => new Response(JSON.stringify(body), { status });
const ok = (item) => ({ response: { header: { resultCode: '00' }, body: { items: { item } } } });

test('fetchDailyPrices — 파싱·정렬·정확 종목 필터·숫자 정규화', async () => {
  stubFetch(() => jsonRes(ok([
    { basDt: '20260702', srtnCd: '005930', clpr: '61,000', trqu: '12,345', mrktTotAmt: '364,000,000,000,000' },
    { basDt: '20260701', srtnCd: '005930', clpr: '60000', trqu: '10000', mrktTotAmt: '360000000000000' },
    { basDt: '20260701', srtnCd: '005935', clpr: '50000', trqu: '1', mrktTotAmt: '1' }, // likeSrtnCd 부분 일치 잡음 — 제외돼야 함
  ])));
  const prices = await krx.fetchDailyPrices({ stockCode: '005930', beginDate: '20260601', endDate: '20260702' });
  assert.equal(prices.length, 2);
  assert.deepEqual(prices.map((p) => p.basDt), ['20260701', '20260702']); // 오름차순
  assert.equal(prices[1].close, 61000);           // 콤마 제거
  assert.equal(prices[1].marketCap, 364e12);
  assert.match(lastUrl, /getStockPriceInfo/);
  assert.match(lastUrl, /likeSrtnCd=005930/);
  assert.match(lastUrl, /beginBasDt=20260601/);
  assert.match(lastUrl, /resultType=json/);
});

test('fetchDailyPrices — 단일 item(비배열)·결측 숫자 허용', async () => {
  stubFetch(() => jsonRes(ok({ basDt: '20260701', srtnCd: '005930', clpr: '', trqu: null, mrktTotAmt: '10' })));
  const prices = await krx.fetchDailyPrices({ stockCode: '005930', beginDate: '20260601', endDate: '20260702' });
  assert.equal(prices.length, 1);
  assert.equal(prices[0].close, null);
  assert.equal(prices[0].marketCap, 10);
});

test('fetchDailyPrices — 오류 경로: 종목코드 형식 / HTTP / resultCode / XML 응답', async () => {
  await assert.rejects(() => krx.fetchDailyPrices({ stockCode: '5930', beginDate: '1', endDate: '2' }), /6자리/);
  stubFetch(() => new Response('down', { status: 500 }));
  await assert.rejects(() => krx.fetchDailyPrices({ stockCode: '005930', beginDate: '1', endDate: '2' }), /HTTP 500/);
  stubFetch(() => jsonRes({ response: { header: { resultCode: '30', resultMsg: 'SERVICE KEY ERROR' } } }));
  await assert.rejects(() => krx.fetchDailyPrices({ stockCode: '005930', beginDate: '1', endDate: '2' }), /resultCode=30/);
  stubFetch(() => new Response('<xml>인증키 오류</xml>', { status: 200 }));
  await assert.rejects(() => krx.fetchDailyPrices({ stockCode: '005930', beginDate: '1', endDate: '2' }), /JSON 파싱 실패/);
});

test('API_KEY / requireKey — env 키 인식', () => {
  assert.equal(krx.API_KEY, 'test-krx-key');
  krx.requireKey();
});
