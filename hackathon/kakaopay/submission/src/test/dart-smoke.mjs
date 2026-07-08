#!/usr/bin/env node
/**
 * DART 세팅 스모크 테스트.
 *  [1] MCP 서버 왕복: initialize → tools/list(6) → dart_status
 *  [2] (DART_API_KEY 있을 때만) 라이브: corp_search 삼성전자 → 00126380
 * 실행: node src/test/dart-smoke.mjs
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

const responses = await callMcpServer(join(here, '..', 'mcp', 'dart-server.mjs'), [
  initializeRequest,
  initializedNotification,
  listToolsRequest,
  toolCall(3, 'dart_status'),
  toolCall(4, 'dart_corp_search', { keyword: '삼성전자' }),
]);

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const search = responses.find(r => r.id === 4);

check('initialize 응답', init?.result?.serverInfo?.name === 'invest-companion-dart');
check('tools/list 6개 도구', list?.result?.tools?.length === 6, `got ${list?.result?.tools?.length}`);
for (const t of ['dart_corp_search', 'dart_company', 'dart_disclosures',
  'dart_financial_summary', 'dart_financial_full', 'dart_status']) {
  check(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

const st = parseToolPayload(status);
check('dart_status 응답', st?.apiKey === 'present' || String(st?.apiKey).startsWith('missing'));

if (st?.apiKey === 'present') {
  const sr = parseToolPayload(search);
  check('라이브: 삼성전자 → 00126380',
    sr?.matches?.some(m => m.corpCode === '00126380' && m.stockCode === '005930'),
    JSON.stringify(sr?.matches?.slice(0, 2)));
} else {
  console.log('  (키 없음 — 라이브 검증 생략)');
}

finish();
