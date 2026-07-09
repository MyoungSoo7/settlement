#!/usr/bin/env node
/**
 * 네이버 뉴스 세팅 스모크 테스트.
 *  [1] MCP 서버 왕복: initialize → tools/list(3) → news_status
 *  [2] (NAVER 키 있을 때만) 라이브: 삼성전자 뉴스 검색 → 기사 ≥ 1건
 * 실행: node src/test/news-smoke.mjs
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

const responses = await callMcpServer(join(here, '..', 'mcp', 'news-server.mjs'), [
  initializeRequest,
  initializedNotification,
  listToolsRequest,
  toolCall(3, 'news_status'),
  toolCall(4, 'news_search_company', { company: '삼성전자', display: 3 }),
]);

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const search = responses.find(r => r.id === 4);

check('initialize 응답', init?.result?.serverInfo?.name === 'invest-companion-news');
check('tools/list 3개 도구', list?.result?.tools?.length === 3, `got ${list?.result?.tools?.length}`);
for (const t of ['news_search_company', 'news_search_risk', 'news_status']) {
  check(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

const st = parseToolPayload(status);
check('news_status 응답', st?.apiKey === 'present' || String(st?.apiKey).startsWith('missing'));
check('기본 악재 키워드 ≥ 5', (st?.defaultRiskKeywords?.length ?? 0) >= 5);

if (st?.apiKey === 'present') {
  const sr = parseToolPayload(search);
  check('라이브: 삼성전자 기사 ≥ 1건', (sr?.items?.length ?? 0) >= 1, JSON.stringify(sr).slice(0, 200));
  check('라이브: 기사 메타데이터(title/url/pubDate)',
    Boolean(sr?.items?.[0]?.title && sr?.items?.[0]?.url && sr?.items?.[0]?.pubDate));
} else {
  console.log('  (키 없음 — 라이브 검증 생략)');
}

finish();
