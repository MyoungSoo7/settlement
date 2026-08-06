#!/usr/bin/env node
/**
 * weather-server 스모크 — 프로토콜 + status, 키가 있으면 실황·브리핑 라이브 검증.
 * 실행: node src/test/weather-smoke.mjs
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  callMcpServer, createAssert, initializeRequest, initializedNotification,
  listToolsRequest, parseToolPayload, toolCall,
} from './smoke-harness.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const server = join(here, '..', 'mcp', 'weather-server.mjs');
const assert = createAssert();

const EXPECTED_TOOLS = ['weather_now', 'weather_outfit_brief', 'weather_status'];

const base = await callMcpServer(server, [
  initializeRequest, initializedNotification, listToolsRequest, toolCall(3, 'weather_status'),
], 6_000);

assert.check('initialize 응답', base.find((r) => r.id === 1)?.result?.serverInfo?.name === 'fashion-first-weather');
const tools = base.find((r) => r.id === 2)?.result?.tools ?? [];
assert.check('tools/list 3종 정확히', tools.length === EXPECTED_TOOLS.length
  && EXPECTED_TOOLS.every((n) => tools.some((t) => t.name === n)), `got ${tools.map((t) => t.name).join(',')}`);

const status = parseToolPayload(base.find((r) => r.id === 3));
assert.check('지원 지역 20곳', status?.regions?.length === 20);
const live = status?.apiKey === 'present';
console.log(`  (키 ${live ? '있음 — 라이브 검증 진행' : '없음 — 프로토콜 검증만'})`);

if (live) {
  const liveResponses = await callMcpServer(server, [
    initializeRequest, initializedNotification,
    toolCall(11, 'weather_now', { region: '서울' }),
    toolCall(12, 'weather_outfit_brief', { region: '부산', days: 2 }),
  ], 25_000);

  const now = parseToolPayload(liveResponses.find((r) => r.id === 11));
  assert.check('weather_now 라이브: 기온 수신', Number.isFinite(now?.tempC));
  assert.check('weather_now 라이브: 옷차림 제안 동봉', typeof now?.dressSuggestion === 'string' && now.dressSuggestion.length > 0);

  const brief = parseToolPayload(liveResponses.find((r) => r.id === 12));
  assert.check('outfit_brief 라이브: 2일 브리핑', brief?.days?.length === 2);
  assert.check('outfit_brief 라이브: 일별 기온·강수 필드', brief?.days?.every((d) => 'minTempC' in d && 'rainChanceMaxPct' in d && 'dressSuggestion' in d));
  assert.check('outfit_brief 라이브: 옷차림 가이드 동봉', Array.isArray(brief?.dressGuide) && brief.dressGuide.length === 8);
}

assert.finish();
