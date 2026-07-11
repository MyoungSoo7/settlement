#!/usr/bin/env node
/**
 * shop-server 스모크 — 프로토콜(initialize/tools/list) + status, 키가 있으면 라이브 검증.
 * 실행: node src/test/shop-smoke.mjs
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  callMcpServer, createAssert, initializeRequest, initializedNotification,
  listToolsRequest, parseToolPayload, toolCall,
} from './smoke-harness.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const server = join(here, '..', 'mcp', 'shop-server.mjs');
const assert = createAssert();

const EXPECTED_TOOLS = ['shop_search', 'price_band', 'brand_snapshot', 'shop_status'];

const calls = [initializeRequest, initializedNotification, listToolsRequest, toolCall(3, 'shop_status')];
const statusOnly = await callMcpServer(server, calls, 6_000);

const init = statusOnly.find((r) => r.id === 1);
assert.check('initialize 응답', init?.result?.serverInfo?.name === 'fashion-first-shop');

const tools = statusOnly.find((r) => r.id === 2)?.result?.tools ?? [];
assert.check(`tools/list 4종 정확히`, tools.length === EXPECTED_TOOLS.length
  && EXPECTED_TOOLS.every((n) => tools.some((t) => t.name === n)), `got ${tools.map((t) => t.name).join(',')}`);
assert.check('모든 도구 readOnlyHint', tools.every((t) => t.annotations?.readOnlyHint === true));

const status = parseToolPayload(statusOnly.find((r) => r.id === 3));
assert.check('shop_status 응답', typeof status?.apiKey === 'string');
const live = status?.apiKey === 'present';
console.log(`  (키 ${live ? '있음 — 라이브 검증 진행' : '없음 — 프로토콜 검증만'})`);

if (live) {
  const liveResponses = await callMcpServer(server, [
    initializeRequest, initializedNotification,
    toolCall(11, 'shop_search', { query: '무신사 스탠다드 셔츠', display: 5 }),
    toolCall(12, 'price_band', { query: '마르디 메크르디 티셔츠', referencePrice: 45000, sample: 60 }),
    toolCall(13, 'brand_snapshot', { brand: '마르디메크르디', sample: 60 }),
  ], 30_000);

  const search = parseToolPayload(liveResponses.find((r) => r.id === 11));
  assert.check('shop_search 라이브: 결과 존재', (search?.items?.length ?? 0) > 0 && search.total > 0);
  assert.check('shop_search 라이브: 정규화 필드', search?.items?.every((i) => 'lprice' in i && 'mallName' in i && 'category' in i));

  const band = parseToolPayload(liveResponses.find((r) => r.id === 12));
  assert.check('price_band 라이브: 분포 통계', band?.priceStats?.median > 0 && band.priceStats.min <= band.priceStats.median);
  assert.check('price_band 라이브: 기준가 퍼센타일', Number.isInteger(band?.reference?.percentile));

  const snap = parseToolPayload(liveResponses.find((r) => r.id === 13));
  assert.check('brand_snapshot 라이브: 카테고리 분포', (snap?.categories?.length ?? 0) > 0 && snap.marketTotal > 0);
}

assert.finish();
