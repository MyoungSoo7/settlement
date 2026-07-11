#!/usr/bin/env node
/**
 * trend-server 스모크 — 프로토콜 + status, 키가 있으면 buzz_trend 라이브 검증.
 * datalab_shopping_trend 는 권한 미등록 환경에서 "구분 가능한 안내 에러"를 내는지까지 확인.
 * 실행: node src/test/trend-smoke.mjs
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  callMcpServer, createAssert, initializeRequest, initializedNotification,
  listToolsRequest, parseToolPayload, toolCall,
} from './smoke-harness.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const server = join(here, '..', 'mcp', 'trend-server.mjs');
const assert = createAssert();

const EXPECTED_TOOLS = ['buzz_trend', 'trend_compare', 'datalab_shopping_trend', 'trend_status'];

const base = await callMcpServer(server, [
  initializeRequest, initializedNotification, listToolsRequest, toolCall(3, 'trend_status'),
], 6_000);

assert.check('initialize 응답', base.find((r) => r.id === 1)?.result?.serverInfo?.name === 'fashion-first-trend');
const tools = base.find((r) => r.id === 2)?.result?.tools ?? [];
assert.check('tools/list 4종 정확히', tools.length === EXPECTED_TOOLS.length
  && EXPECTED_TOOLS.every((n) => tools.some((t) => t.name === n)), `got ${tools.map((t) => t.name).join(',')}`);

const status = parseToolPayload(base.find((r) => r.id === 3));
assert.check('trend_status 판정 기준 명시', String(status?.directionRule ?? '').includes('1.3'));
const live = status?.apiKey === 'present';
console.log(`  (키 ${live ? '있음 — 라이브 검증 진행' : '없음 — 프로토콜 검증만'})`);

if (live) {
  const liveResponses = await callMcpServer(server, [
    initializeRequest, initializedNotification,
    toolCall(11, 'buzz_trend', { keyword: '무신사', months: 6 }),
    toolCall(12, 'datalab_shopping_trend', { keywords: ['셔츠'], months: 3 }),
  ], 45_000);

  const buzz = parseToolPayload(liveResponses.find((r) => r.id === 11));
  assert.check('buzz_trend 라이브: 시계열 6개월', buzz?.series?.length === 6);
  assert.check('buzz_trend 라이브: 방향 판정', ['rising', 'falling', 'flat', 'rising-from-zero', 'insufficient-data'].includes(buzz?.direction));
  assert.check('buzz_trend 라이브: 커버리지 정직성 필드', typeof buzz?.coverageComplete === 'boolean' && 'oldestFetched' in (buzz ?? {}));

  const datalab = liveResponses.find((r) => r.id === 12);
  const datalabText = datalab?.result?.content?.[0]?.text ?? '';
  const datalabOk = datalab?.result?.isError !== true;
  assert.check('datalab: 성공 또는 권한 안내 에러', datalabOk || datalabText.includes('데이터랩 권한 미등록'),
    datalabText.slice(0, 120));
  console.log(`  (datalab_shopping_trend: ${datalabOk ? '권한 있음 — 실데이터 수신' : '권한 없음 — 안내 에러 확인'})`);
}

assert.finish();
