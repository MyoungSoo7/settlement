import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { callServer, withFetchStub } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, '..', '..');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');
const DART_SERVER = join(SRC, 'mcp', 'dart-server.mjs');
const ECOS_SERVER = join(SRC, 'mcp', 'ecos-server.mjs');
const NEWS_SERVER = join(SRC, 'mcp', 'news-server.mjs');
const REGISTRY_SERVER = join(SRC, 'mcp', 'registry-server.mjs');

const workDir = mkdtempSync(join(tmpdir(), 'tca-mcp-'));
test.after(() => rmSync(workDir, { recursive: true, force: true }));

const rpc = (id, method, params) => ({ jsonrpc: '2.0', id, method, ...(params ? { params } : {}) });
const toolCall = (id, name, args = {}) => rpc(id, 'tools/call', { name, arguments: args });
const byId = (responses, id) => responses.find((r) => r.id === id);
const payload = (r) => JSON.parse(r.result.content[0].text);

test('dart MCP 서버 — initialize/tools/list/6개 도구 왕복 + 오류 경로', async () => {
  // 캐시 픽스처 — dart_status·dart_corp_search 가 네트워크 없이 동작하도록
  const cachePath = join(workDir, 'dart-cache.json');
  writeFileSync(cachePath, JSON.stringify({
    fetchedAt: new Date().toISOString(),
    totalCount: 2, listedCount: 2,
    companies: [
      { corpCode: '00126380', name: '삼성전자', stockCode: '005930', modifyDate: '20260101' },
      { corpCode: '00164779', name: 'SK하이닉스', stockCode: '000660', modifyDate: '20260101' },
    ],
  }));
  const stubFile = join(workDir, 'dart-stub.json');
  writeFileSync(stubFile, JSON.stringify({
    rules: [
      { match: 'corp_code=99999999', json: { status: '100', message: '잘못된 키' } },
      { match: 'company.json', json: { status: '000', corp_name: '삼성전자' } },
      { match: 'list.json', json: { status: '000', list: [{ report_nm: '분기보고서' }] } },
      { match: 'fnlttSinglAcntAll.json', json: { status: '013', message: '조회된 데이타가 없습니다' } },
      { match: 'fnlttSinglAcnt.json', json: { status: '000', list: [{ account_nm: '매출액' }] } },
    ],
  }));

  const responses = await callServer(DART_SERVER, [
    rpc(1, 'initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 't', version: '0' } }),
    { jsonrpc: '2.0', method: 'notifications/initialized' },
    'this is not json',                       // 무시되어야 한다
    rpc(2, 'tools/list'),
    toolCall(3, 'dart_status'),
    toolCall(4, 'dart_corp_search', { keyword: '삼성', limit: 1 }),
    toolCall(5, 'dart_company', { corp_code: '00126380' }),
    toolCall(6, 'dart_disclosures', { corp_code: '00126380', days: 10, pblntf_ty: 'A', page_count: 5 }),
    toolCall(7, 'dart_financial_summary', { corp_code: '00126380', year: 2025 }),
    toolCall(8, 'dart_financial_full', { corp_code: '00126380', year: 2025, fs_div: 'OFS' }),
    toolCall(9, 'dart_company', { corp_code: '99999999' }),   // DART status 100 → tool error
    toolCall(10, 'no_such_tool'),                              // unknown tool → isError
    rpc(11, 'no/such/method'),                                 // → -32601
  ], {
    env: withFetchStub(PRELOAD, stubFile, { DART_API_KEY: 'test-key', CORP_CODES_CACHE: cachePath }),
    expectedResponses: 11,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'trusted-ceo-agent-dart');
  assert.equal(byId(responses, 2).result.tools.length, 6);
  assert.deepEqual(
    byId(responses, 2).result.tools.map((t) => t.name),
    ['dart_corp_search', 'dart_company', 'dart_disclosures', 'dart_financial_summary', 'dart_financial_full', 'dart_status'],
  );
  const status = payload(byId(responses, 3));
  assert.equal(status.apiKey, 'present');
  assert.equal(status.corpCodeCache.listedCount, 2);
  assert.equal(payload(byId(responses, 4)).matches[0].corpCode, '00126380');
  assert.equal(payload(byId(responses, 5)).corp_name, '삼성전자');
  assert.equal(payload(byId(responses, 6)).list[0].report_nm, '분기보고서');
  assert.equal(payload(byId(responses, 7)).list[0].account_nm, '매출액');
  assert.equal(payload(byId(responses, 8)).status, '013');
  const toolError = byId(responses, 9);
  assert.equal(toolError.result.isError, true);
  assert.match(toolError.result.content[0].text, /status 100/);
  assert.equal(byId(responses, 10).result.isError, true);
  assert.match(byId(responses, 10).result.content[0].text, /unknown tool/);
  assert.equal(byId(responses, 11).error.code, -32601);
});

test('ecos MCP 서버 — initialize/tools/list/4개 도구 왕복', async () => {
  const stubFile = join(workDir, 'ecos-stub.json');
  writeFileSync(stubFile, JSON.stringify({
    rules: [
      { match: 'StatisticSearch', json: { StatisticSearch: { row: [
        { TIME: '20260601', DATA_VALUE: '2.50' }, { TIME: '20260602', DATA_VALUE: '2.75' },
      ] } } },
      { match: 'KeyStatisticList', json: { KeyStatisticList: { row: [
        { CLASS_NAME: '금리', KEYSTAT_NAME: '기준금리', DATA_VALUE: '2.5', UNIT_NAME: '%', CYCLE: '20260601' },
      ] } } },
    ],
  }));

  const responses = await callServer(ECOS_SERVER, [
    rpc(1, 'initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 't', version: '0' } }),
    rpc(2, 'tools/list'),
    toolCall(3, 'ecos_status'),
    toolCall(4, 'ecos_indicator', { code: 'BASE_RATE', months_back: 2 }),
    toolCall(5, 'ecos_series', { stat_code: '722Y001', item_code: '0101000', cycle: 'D', start: '20260601', end: '20260630' }),
    toolCall(6, 'ecos_key_stats'),
  ], {
    env: withFetchStub(PRELOAD, stubFile, { ECOS_API_KEY: 'test-key' }),
    expectedResponses: 6,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'trusted-ceo-agent-ecos');
  assert.deepEqual(
    byId(responses, 2).result.tools.map((t) => t.name),
    ['ecos_indicator', 'ecos_series', 'ecos_key_stats', 'ecos_status'],
  );
  const status = payload(byId(responses, 3));
  assert.equal(status.apiKey, 'present');
  assert.match(status.catalog.BASE_RATE, /722Y001/);
  const indicator = payload(byId(responses, 4));
  assert.equal(indicator.latest.value, 2.75);
  assert.equal(indicator.changeFromFirst, 0.25);
  assert.equal(payload(byId(responses, 5)).length, 2);
  assert.equal(payload(byId(responses, 6))[0].name, '기준금리');
});

test('news MCP 서버 — initialize/tools/list/3개 도구 왕복', async () => {
  const stubFile = join(workDir, 'news-stub.json');
  writeFileSync(stubFile, JSON.stringify({
    rules: [
      { match: 'search/news.json', json: {
        total: 1,
        start: 1,
        display: 1,
        items: [
          {
            title: '<b>PFCT</b> 투자유치',
            originallink: 'https://news.example.com/pfct',
            link: 'https://n.news.naver.com/article/123',
            description: '핀테크 기업 PFCT가 <b>투자유치</b>를 진행했다.',
            pubDate: 'Tue, 07 Jul 2026 10:30:00 +0900',
          },
        ],
      } },
    ],
  }));

  const responses = await callServer(NEWS_SERVER, [
    rpc(1, 'initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 't', version: '0' } }),
    rpc(2, 'tools/list'),
    toolCall(3, 'news_status'),
    toolCall(4, 'news_search_company', { company: 'PFCT', display: 1 }),
    toolCall(5, 'news_search_risk', { company: 'PFCT', keywords: ['규제'], display: 1 }),
  ], {
    env: withFetchStub(PRELOAD, stubFile, { NAVER_CLIENT_ID: 'test-client', NAVER_CLIENT_SECRET: 'test-secret' }),
    expectedResponses: 5,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'trusted-ceo-agent-news');
  assert.deepEqual(
    byId(responses, 2).result.tools.map((t) => t.name),
    ['news_search_company', 'news_search_risk', 'news_status'],
  );
  const status = payload(byId(responses, 3));
  assert.equal(status.apiKey, 'present');
  assert.match(status.queryExample, /PFCT/);
  const company = payload(byId(responses, 4));
  assert.equal(company.items[0].title, 'PFCT 투자유치');
  assert.equal(company.items[0].url, 'https://news.example.com/pfct');
  const risk = payload(byId(responses, 5));
  assert.match(risk.query, /PFCT 규제/);
});

test('registry MCP 서버 — initialize/tools/list/5개 도구 왕복', async () => {
  const stubFile = join(workDir, 'registry-stub.json');
  writeFileSync(stubFile, JSON.stringify({
    rules: [
      { match: '/status', json: { data: [{ b_no: '1248100998', b_stt: '계속사업자', b_stt_cd: '01' }] } },
      { match: '/validate', json: { data: [{ b_no: '1248100998', valid: '01', valid_msg: '확인되었습니다.' }] } },
    ],
  }));

  const responses = await callServer(REGISTRY_SERVER, [
    rpc(1, 'initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 't', version: '0' } }),
    rpc(2, 'tools/list'),
    toolCall(3, 'registry_status'),
    toolCall(4, 'business_number_validate', { b_no: '124-81-00998' }),
    toolCall(5, 'business_status_check', { b_no: '124-81-00998' }),
    toolCall(6, 'business_auth_check', { b_no: '124-81-00998', start_dt: '19690113', p_nm: '홍길동', b_nm: '삼성전자' }),
    toolCall(7, 'company_identity_gate', {
      company_name: '삼성전자',
      business_number: '124-81-00998',
      representative_name: '홍길동',
      opening_date: '19690113',
      stock_code: '005930',
    }),
  ], {
    env: withFetchStub(PRELOAD, stubFile, { DATA_GO_KR_API_KEY: 'test-service-key' }),
    expectedResponses: 7,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'trusted-ceo-agent-registry');
  assert.deepEqual(
    byId(responses, 2).result.tools.map((t) => t.name),
    ['business_number_validate', 'business_status_check', 'business_auth_check', 'company_identity_gate', 'registry_status'],
  );
  assert.equal(payload(byId(responses, 3)).apiKey, 'present');
  assert.equal(payload(byId(responses, 4)).valid, true);
  assert.equal(payload(byId(responses, 5)).data[0].b_stt_cd, '01');
  assert.equal(payload(byId(responses, 6)).data[0].valid, '01');
  const gate = payload(byId(responses, 7));
  assert.equal(gate.analysisAllowed, true);
  assert.equal(gate.identifiers.stockCode, '005930');
});
