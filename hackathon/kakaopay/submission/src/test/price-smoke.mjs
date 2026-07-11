#!/usr/bin/env node
/**
 * 시세 축 스모크 테스트.
 *  [1] MCP 서버 왕복: initialize → tools/list(3) → price_status
 *  [2] 라이브: 삼성전자(005930) 현재가 > 0, KOSPI 해석 / history streak 구조
 *  (키 불필요 — 네트워크 실패 시에만 라이브 생략)
 * 실행: node src/test/price-smoke.mjs
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  callMcpServer,
  createAssert,
  initializedNotification,
  initializeRequest,
  listToolsRequest,
  parseToolPayload,
  toolCall,
} from './smoke-harness.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const { check, finish } = createAssert();

const responses = await callMcpServer(join(here, '..', 'mcp', 'price-server.mjs'), [
  initializeRequest,
  initializedNotification,
  listToolsRequest,
  toolCall(3, 'price_status'),
  toolCall(4, 'price_quote', { stockCode: '005930' }),
  toolCall(5, 'price_history', { stockCode: '005930', days: 10 }),
  toolCall(6, 'backtest_stats'),
  toolCall(7, 'plan_trade', { stockCode: '005930', budget: 3000000 }),
  toolCall(8, 'universe_list'),
], 15_000);

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const quoteRes = responses.find(r => r.id === 4);
const historyRes = responses.find(r => r.id === 5);

check('initialize 응답', init?.result?.serverInfo?.name === 'invest-companion-price');
check('tools/list 6개 도구', list?.result?.tools?.length === 6, `got ${list?.result?.tools?.length}`);
for (const t of ['price_quote', 'price_history', 'price_status', 'plan_trade', 'backtest_stats', 'universe_list']) {
  check(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

const uni = parseToolPayload(responses.find(r => r.id === 8));
check('universe_list: 60종목 이상 + 생존편향 주의 문구',
  (uni?.symbols?.length ?? 0) >= 60 && String(uni?.caveat).includes('생존 편향'));

const bt = parseToolPayload(responses.find(r => r.id === 6));
check('backtest_stats: 주기 3종 + 표본 수천 건',
  ['month', 'quarter', 'year'].every(k => (bt?.horizons?.[k]?.samples ?? 0) > 1000));
check('backtest_stats: 승률이 정직한 범위(40~70%)',
  ['month', 'quarter', 'year'].every(k => bt.horizons[k].winRate > 0.4 && bt.horizons[k].winRate < 0.7));
check('backtest_stats: 방법론·한계 서술 포함',
  (bt?.methodology ?? []).some(m => m.includes('보장하지 않는다')));

const st = parseToolPayload(status);
check('price_status 응답', st?.apiKey === 'not-required' && Boolean(st?.source));

if (quoteRes?.result?.isError) {
  console.log(`  (네트워크 불가 — 라이브 검증 생략: ${JSON.stringify(quoteRes.result.content?.[0]?.text).slice(0, 120)})`);
} else {
  const q = parseToolPayload(quoteRes);
  check('라이브: 005930 현재가 > 0', (q?.price ?? 0) > 0, JSON.stringify(q).slice(0, 200));
  check('라이브: KOSPI 해석 + asOf/source 명시', q?.market === 'KOSPI' && Boolean(q?.asOf) && Boolean(q?.source));

  const h = parseToolPayload(historyRes);
  check('라이브: history 종가 ≥ 5건', (h?.closes?.length ?? 0) >= 5, JSON.stringify(h?.streak));
  check('라이브: streak 구조(direction/days)',
    ['up', 'down', 'flat'].includes(h?.streak?.direction) && Number.isInteger(h?.streak?.days));

  const plan = parseToolPayload(responses.find(r => r.id === 7));
  check('라이브: plan_trade 3분할 밴드 + 손절/익절 기준가',
    plan?.plan?.feasible === true
      && plan.plan.entries?.length === 3
      && plan.plan.exits?.stopLoss?.price > 0
      && plan.plan.exits?.takeProfitFirst?.price > plan.plan.exits.stopLoss.price,
    JSON.stringify(plan?.plan).slice(0, 200));
}

finish();
