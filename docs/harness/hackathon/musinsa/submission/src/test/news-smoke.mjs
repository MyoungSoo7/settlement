#!/usr/bin/env node
/**
 * news-server 스모크 — 프로토콜 + status, 키가 있으면 리스크/기회 스캔 라이브 검증.
 * 실행: node src/test/news-smoke.mjs
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  callMcpServer, createAssert, initializeRequest, initializedNotification,
  listToolsRequest, parseToolPayload, toolCall,
} from './smoke-harness.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const server = join(here, '..', 'mcp', 'news-server.mjs');
const assert = createAssert();

const EXPECTED_TOOLS = ['fashion_news', 'brand_risk_scan', 'brand_opportunity_scan', 'brand_recall_check', 'recall_latest', 'news_status'];

const base = await callMcpServer(server, [
  initializeRequest, initializedNotification, listToolsRequest, toolCall(3, 'news_status'),
], 6_000);

assert.check('initialize 응답', base.find((r) => r.id === 1)?.result?.serverInfo?.name === 'fashion-first-news');
const tools = base.find((r) => r.id === 2)?.result?.tools ?? [];
assert.check('tools/list 6종 정확히', tools.length === EXPECTED_TOOLS.length
  && EXPECTED_TOOLS.every((n) => tools.some((t) => t.name === n)), `got ${tools.map((t) => t.name).join(',')}`);

const status = parseToolPayload(base.find((r) => r.id === 3));
assert.check('기본 리스크 키워드 6종', status?.defaultRiskKeywords?.length === 6);
assert.check('기본 기회 키워드 5종', status?.defaultOpportunityKeywords?.length === 5);
const live = status?.apiKey === 'present';
console.log(`  (키 ${live ? '있음 — 라이브 검증 진행' : '없음 — 프로토콜 검증만'})`);

if (live) {
  const liveResponses = await callMcpServer(server, [
    initializeRequest, initializedNotification,
    toolCall(11, 'fashion_news', { query: '무신사', display: 5 }),
    toolCall(12, 'brand_opportunity_scan', { brand: '나이키', display: 3, keywords: ['콜라보', '한정판'] }),
  ], 30_000);

  const news = parseToolPayload(liveResponses.find((r) => r.id === 11));
  assert.check('fashion_news 라이브: 결과 존재', (news?.items?.length ?? 0) > 0);
  assert.check('fashion_news 라이브: 본문 미수집(메타만)', news?.items?.every((i) => !('content' in i) && 'pubDate' in i && 'url' in i));

  const opp = parseToolPayload(liveResponses.find((r) => r.id === 12));
  assert.check('brand_opportunity_scan 라이브: 키워드별 검색 병합', (opp?.perKeyword?.length ?? 0) === 2 && Array.isArray(opp?.items));
}

if (String(status?.recallApiKey ?? '').startsWith('present')) {
  // 공식 리콜 DB — 키는 있으나 소비자24 반영 전(코드 50)일 수 있어 두 상태 모두 허용
  const recallResponses = await callMcpServer(server, [
    initializeRequest, initializedNotification,
    toolCall(21, 'recall_latest', { category: '화장품', display: 3 }),
  ], 25_000);
  const recall = recallResponses.find((r) => r.id === 21);
  const recallText = recall?.result?.content?.[0]?.text ?? '';
  const recallOk = recall?.result?.isError !== true;
  assert.check('recall_latest: 성공 또는 키 미등록 안내 에러', recallOk || recallText.includes('CONSUMER24_API_KEY'), recallText.slice(0, 120));
  console.log(`  (recall_latest: ${recallOk ? '소비자24 키 등록됨 — 실데이터 수신' : '키 미등록 — 발급 안내 에러 확인'})`);
}

assert.finish();
