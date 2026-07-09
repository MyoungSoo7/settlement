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
], 15_000);

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const quoteRes = responses.find(r => r.id === 4);
const historyRes = responses.find(r => r.id === 5);

check('initialize 응답', init?.result?.serverInfo?.name === 'invest-companion-price');
check('tools/list 3개 도구', list?.result?.tools?.length === 3, `got ${list?.result?.tools?.length}`);
for (const t of ['price_quote', 'price_history', 'price_status']) {
  check(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

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
}

finish();
