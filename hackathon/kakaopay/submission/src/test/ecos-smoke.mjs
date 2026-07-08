#!/usr/bin/env node
/**
 * ECOS 세팅 스모크 테스트.
 *  [1] MCP 서버 왕복: initialize → tools/list(4) → ecos_status
 *  [2] (ECOS_API_KEY 있을 때만) 라이브: ecos_indicator BASE_RATE → 관측치 ≥ 1
 * 실행: node src/test/ecos-smoke.mjs
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

const responses = await callMcpServer(join(here, '..', 'mcp', 'ecos-server.mjs'), [
  initializeRequest,
  initializedNotification,
  listToolsRequest,
  toolCall(3, 'ecos_status'),
  toolCall(4, 'ecos_indicator', { code: 'BASE_RATE', months_back: 2 }),
]);

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const series = responses.find(r => r.id === 4);

check('initialize 응답', init?.result?.serverInfo?.name === 'invest-companion-ecos');
check('tools/list 4개 도구', list?.result?.tools?.length === 4, `got ${list?.result?.tools?.length}`);
for (const t of ['ecos_indicator', 'ecos_series', 'ecos_key_stats', 'ecos_status']) {
  check(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

const st = parseToolPayload(status);
check('ecos_status 응답', st?.apiKey === 'present' || String(st?.apiKey).startsWith('missing'));
check('카탈로그 4개 지표', Object.keys(st?.catalog ?? {}).length === 4);

if (st?.apiKey === 'present') {
  const sr = parseToolPayload(series);
  check('라이브: BASE_RATE 관측치 ≥ 1',
    sr?.count >= 1 && typeof sr?.latest?.value === 'number',
    JSON.stringify({ count: sr?.count, latest: sr?.latest }));
} else {
  console.log('  (키 없음 — 라이브 검증 생략)');
}

finish();
