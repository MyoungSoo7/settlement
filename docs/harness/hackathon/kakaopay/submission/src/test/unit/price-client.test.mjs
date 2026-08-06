/**
 * price 클라이언트 단위테스트 — 재시도(429 백오프)·TTL 캐시·KQ 조기 생략·거래정지 휴리스틱.
 * globalThis.fetch 를 프로세스 내에서 스텁해 네트워크 0 으로 검증한다.
 * 실행: node src/test/run-all.mjs (개별: node src/test/unit/price-client.test.mjs)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { quote, detectSuspectedHalt, pickFreshest, clearChartCache } from '../../price/client.mjs';

const realFetch = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = realFetch; clearChartCache(); });

const nowSec = () => Math.floor(Date.now() / 1000);
const chartBody = ({ price = 72000, time = nowSec() } = {}) => JSON.stringify({
  chart: { result: [{
    meta: { regularMarketPrice: price, regularMarketTime: time, chartPreviousClose: price - 1000, currency: 'KRW' },
    timestamp: [time - 86_400, time],
    indicators: { quote: [{ close: [price - 1000, price], volume: [100, 100] }] },
  }] },
});

function stubFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    return handler(String(url), calls.length);
  };
  return calls;
}

test('429 는 백오프 후 재시도해 성공한다', async () => {
  const calls = stubFetch((url, n) => (n === 1
    ? new Response('rate limited', { status: 429 })
    : new Response(chartBody(), { status: 200 })));
  const q = await quote('005930');
  assert.equal(q.price, 72000);
  assert.equal(calls.length, 2);                       // 1회 실패 + 1회 재시도 (KQ 는 조기 생략)
});

test('404(없는 심볼)는 재시도하지 않는다 — KS/KQ 각 1콜로 즉시 실패', async () => {
  const calls = stubFetch(() => new Response(JSON.stringify({ chart: { error: { description: 'No data found' } } }), { status: 404 }));
  await assert.rejects(() => quote('999999'), /시세를 찾지 못했습니다/);
  assert.equal(calls.length, 2);                       // KS 1콜 + KQ 1콜, 재시도 0
});

test('KS 가 신선하면 KQ 를 호출하지 않고, 60초 캐시로 재조회도 0콜', async () => {
  const calls = stubFetch(() => new Response(chartBody(), { status: 200 }));
  await quote('005930');
  assert.deepEqual(calls.map((u) => u.includes('.KQ')), [false]);   // KQ 미호출
  await quote('005930');                                            // 캐시 히트
  assert.equal(calls.length, 1);
});

test('KS 가 낡았으면 KQ 를 추가 확인해 신선한 쪽을 채택한다', async () => {
  const old = nowSec() - 400 * 86_400;
  stubFetch((url) => new Response(
    chartBody(url.includes('.KS') ? { price: 30000, time: old } : { price: 180000 }),
    { status: 200 },
  ));
  const q = await quote('058470');
  assert.equal(q.market, 'KOSDAQ');
  assert.equal(q.price, 180000);
  assert.equal(q.staleWarning, undefined);
});

test('detectSuspectedHalt — 거래량 0 연속 AND 종가 동결일 때만 true (저유동 오탐 방지)', () => {
  const result = (volumes, closes) => ({
    indicators: { quote: [{ close: closes, volume: volumes }] },
  });
  assert.equal(detectSuspectedHalt(result([100, 0, 0], [5000, 5000, 5000])), true);
  // 거래량 0 이어도 종가가 움직였으면 정지가 아니다 — 저유동 소형주 오탐 방지
  assert.equal(detectSuspectedHalt(result([100, 0, 0], [5000, 5001, 5002])), false);
  assert.equal(detectSuspectedHalt(result([0, 0, 100], [5000, 5000, 5000])), false);   // 마지막이 정상 거래
  assert.equal(detectSuspectedHalt(result([100, 100, 100], [5000, 5000, 5000])), false); // 거래량 정상
  assert.equal(detectSuspectedHalt(result([0], [5000])), false);                       // 표본 1개는 판정 보류
  // 결측(null) 종가 행은 건너뛰고 유효 행만 본다
  assert.equal(detectSuspectedHalt(result([0, 999, 0], [5000, null, 5000])), true);
});

test('pickFreshest — 모두 낡았으면 가장 최근 것 + stale 표시 (기존 계약 유지)', () => {
  const c = (t) => ({ result: { meta: { regularMarketTime: t } } });
  const now = Date.now();
  const oldA = c(Math.floor((now - 100 * 86_400_000) / 1000));
  const oldB = c(Math.floor((now - 30 * 86_400_000) / 1000));
  const picked = pickFreshest([oldA, oldB], now);
  assert.equal(picked.stale, true);
  assert.equal(picked.marketTimeMs, oldB.result.meta.regularMarketTime * 1000);
});
