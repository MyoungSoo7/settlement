/**
 * MCP 서버 4종 단위테스트 — 네트워크 0 (fetch 스텁), 라이브 키 불필요.
 * initialize/tools/list/tools/call 왕복과 오류 경로(원격 오류 → isError,
 * unknown tool, -32601)를 스모크(라이브)와 별개로 결정론 검증한다.
 * 실행: node src/test/run-all.mjs (개별: node src/test/unit/mcp-servers.test.mjs)
 */
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
const PRICE_SERVER = join(SRC, 'mcp', 'price-server.mjs');

const workDir = mkdtempSync(join(tmpdir(), 'kic-mcp-'));
test.after(() => rmSync(workDir, { recursive: true, force: true }));

const rpc = (id, method, params) => ({ jsonrpc: '2.0', id, method, ...(params ? { params } : {}) });
const toolCall = (id, name, args = {}) => rpc(id, 'tools/call', { name, arguments: args });
const byId = (responses, id) => responses.find((r) => r.id === id);
const payload = (r) => JSON.parse(r.result.content[0].text);

test('dart MCP 서버 — initialize/tools/list/7개 도구 왕복 + 오류 경로 + 출처 메타', async () => {
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
    toolCall(9, 'sector_suitability', { sector: '존재하지않는산업군' }),
    toolCall(10, 'dart_company', { corp_code: '99999999' }),   // DART status 100 → tool error
    toolCall(11, 'no_such_tool'),                              // unknown tool → isError
    rpc(12, 'no/such/method'),                                 // → -32601
  ], {
    env: withFetchStub(PRELOAD, stubFile, { DART_API_KEY: 'test-key', CORP_CODES_CACHE: cachePath }),
    expectedResponses: 12,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'invest-companion-dart');
  assert.deepEqual(
    byId(responses, 2).result.tools.map((t) => t.name),
    ['dart_corp_search', 'dart_company', 'dart_disclosures', 'dart_financial_summary', 'dart_financial_full', 'sector_suitability', 'dart_status'],
  );
  // 모든 도구는 읽기 전용 — annotations 로 명시되어야 한다
  assert.equal(byId(responses, 2).result.tools[0].annotations.readOnlyHint, true);
  const status = payload(byId(responses, 3));
  assert.equal(status.apiKey, 'present');
  assert.equal(status.corpCodeCache.listedCount, 2);
  assert.equal(payload(byId(responses, 4)).matches[0].corpCode, '00126380');
  const company = payload(byId(responses, 5));
  assert.equal(company.corp_name, '삼성전자');
  assert.match(company.source, /DART OpenAPI/);           // 출처·시점 메타 강제
  assert.ok(company.retrievedAt);
  assert.equal(payload(byId(responses, 6)).list[0].report_nm, '분기보고서');
  assert.match(payload(byId(responses, 6)).source, /DART/);
  assert.equal(payload(byId(responses, 7)).list[0].account_nm, '매출액');
  assert.equal(payload(byId(responses, 8)).status, '013');
  // sector_suitability — 사전계산 파일은 저장소에 실재. 없는 산업군은 availableSectors 안내
  const sector = payload(byId(responses, 9));
  assert.ok(sector.error ? Array.isArray(sector.availableSectors) : Array.isArray(sector.sectors));
  const toolError = byId(responses, 10);
  assert.equal(toolError.result.isError, true);
  assert.match(toolError.result.content[0].text, /status 100/);
  assert.equal(byId(responses, 11).result.isError, true);
  assert.match(byId(responses, 11).result.content[0].text, /unknown tool/);
  assert.equal(byId(responses, 12).error.code, -32601);
});

test('ecos MCP 서버 — 4개 도구 왕복 + 경로 파라미터 검증 + 출처 메타', async () => {
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
    toolCall(7, 'ecos_series', { stat_code: '../etc/passwd', item_code: '0101000', cycle: 'D', start: '20260601', end: '20260630' }),
    toolCall(8, 'ecos_series', { stat_code: '722Y001', item_code: '0101000', cycle: 'X', start: '20260601', end: '20260630' }),
  ], {
    env: withFetchStub(PRELOAD, stubFile, { ECOS_API_KEY: 'test-key' }),
    expectedResponses: 8,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'invest-companion-ecos');
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
  assert.match(indicator.source, /ECOS/);                  // 출처·시점 메타 강제
  const series = payload(byId(responses, 5));
  assert.equal(series.count, 2);
  assert.equal(series.rows[1].value, 2.75);
  assert.ok(series.retrievedAt);
  assert.equal(payload(byId(responses, 6)).rows[0].name, '기준금리');
  // URL 경로 파라미터 검증 — ../ 삽입과 잘못된 cycle 은 호출 전에 거부
  assert.equal(byId(responses, 7).result.isError, true);
  assert.match(byId(responses, 7).result.content[0].text, /statCode 형식/);
  assert.equal(byId(responses, 8).result.isError, true);
  assert.match(byId(responses, 8).result.content[0].text, /cycle/);
});

test('news MCP 서버 — 3개 도구 왕복 + 수집 시점 메타', async () => {
  const stubFile = join(workDir, 'news-stub.json');
  writeFileSync(stubFile, JSON.stringify({
    rules: [
      { match: 'search/news.json', json: {
        total: 1, start: 1, display: 1,
        items: [{
          title: '<b>삼성전자</b> 실적 발표',
          originallink: 'https://news.example.com/samsung',
          link: 'https://n.news.naver.com/article/123',
          description: '삼성전자가 <b>실적</b>을 발표했다.',
          pubDate: 'Tue, 07 Jul 2026 10:30:00 +0900',
        }],
      } },
    ],
  }));

  const responses = await callServer(NEWS_SERVER, [
    rpc(1, 'initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 't', version: '0' } }),
    rpc(2, 'tools/list'),
    toolCall(3, 'news_status'),
    toolCall(4, 'news_search_company', { company: '삼성전자', display: 1 }),
    toolCall(5, 'news_search_risk', { company: '삼성전자', keywords: ['유상증자'], display: 1 }),
  ], {
    env: withFetchStub(PRELOAD, stubFile, { NAVER_CLIENT_ID: 'test-client', NAVER_CLIENT_SECRET: 'test-secret' }),
    expectedResponses: 5,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'invest-companion-news');
  assert.deepEqual(
    byId(responses, 2).result.tools.map((t) => t.name),
    ['news_search_company', 'news_search_risk', 'news_status'],
  );
  assert.equal(payload(byId(responses, 3)).apiKey, 'present');
  const company = payload(byId(responses, 4));
  assert.equal(company.items[0].title, '삼성전자 실적 발표');   // HTML 태그 제거 확인
  assert.equal(company.items[0].url, 'https://news.example.com/samsung');
  assert.match(company.source, /네이버 뉴스/);                 // 수집 시점 메타 강제
  assert.ok(company.retrievedAt);
  const risk = payload(byId(responses, 5));
  assert.match(risk.query, /삼성전자 유상증자/);
  assert.equal(risk.total, 1);
  assert.ok(risk.retrievedAt);
});

test('price MCP 서버 — 6개 도구 왕복 + 신선도/거래정지 가드 + ENOENT 안내', async () => {
  const nowSec = Math.floor(Date.now() / 1000);
  const daySec = 86_400;
  const chart = ({ price, time, closes, volumes, name }) => ({
    chart: { result: [{
      meta: {
        regularMarketPrice: price, regularMarketTime: time, chartPreviousClose: closes[closes.length - 2],
        currency: 'KRW', longName: name, fiftyTwoWeekHigh: price * 1.3, fiftyTwoWeekLow: price * 0.7,
      },
      timestamp: closes.map((_, i) => time - (closes.length - 1 - i) * daySec),
      indicators: { quote: [{ close: closes, volume: volumes }] },
    }] },
  });
  const stubFile = join(workDir, 'price-stub.json');
  writeFileSync(stubFile, JSON.stringify({
    rules: [
      // 신선한 KOSPI 종목 — KS 만 조회되고 KQ 는 호출되지 않아야 한다 (호출량 절반 최적화)
      { match: '005930.KS', json: chart({ price: 72000, time: nowSec, closes: [70000, 71000, 72000], volumes: [100, 100, 100], name: '삼성전자' }) },
      { match: '005930.KQ', status: 599, json: { error: 'KS 가 신선하면 이 호출은 나가면 안 된다' } },
      // 코스닥 잔재 심볼 케이스 — .KS 는 낡은 시세(2024), .KQ 가 신선 → KQ 채택
      { match: '058470.KS', json: chart({ price: 30000, time: nowSec - 400 * daySec, closes: [29000, 30000], volumes: [50, 50], name: '리노공업(잔재)' }) },
      { match: '058470.KQ', json: chart({ price: 180000, time: nowSec, closes: [175000, 178000, 180000], volumes: [80, 80, 80], name: '리노공업' }) },
      // 거래정지 의심 — 최근 거래량 0 연속
      { match: '000001.KS', json: chart({ price: 5000, time: nowSec, closes: [5000, 5000, 5000], volumes: [100, 0, 0], name: '정지의심' }) },
    ],
  }));

  const responses = await callServer(PRICE_SERVER, [
    rpc(1, 'initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 't', version: '0' } }),
    rpc(2, 'tools/list'),
    toolCall(3, 'price_status'),
    toolCall(4, 'price_quote', { stockCode: '005930' }),
    toolCall(5, 'price_history', { stockCode: '005930', days: 3 }),
    toolCall(6, 'plan_trade', { stockCode: '005930', budget: 3_000_000 }),
    toolCall(7, 'price_quote', { stockCode: '058470' }),
    toolCall(8, 'price_quote', { stockCode: '000001' }),
    toolCall(9, 'plan_trade', { stockCode: '000001', budget: 1_000_000 }),
    toolCall(10, 'backtest_stats'),
    toolCall(11, 'universe_list'),
    toolCall(12, 'price_quote', { stockCode: 'abc' }),      // 입력 검증 → isError
    toolCall(13, 'no_such_tool'),                            // 디스패처 대칭 — dart 서버와 동일 오류 경로
    rpc(14, 'no/such/method'),                               // → -32601
  ], {
    env: withFetchStub(PRELOAD, stubFile),
    expectedResponses: 14,
  });

  assert.equal(byId(responses, 1).result.serverInfo.name, 'invest-companion-price');
  assert.deepEqual(
    byId(responses, 2).result.tools.map((t) => t.name),
    ['price_quote', 'price_history', 'plan_trade', 'backtest_stats', 'universe_list', 'price_status'],
  );
  assert.equal(payload(byId(responses, 3)).apiKey, 'not-required');
  const quote = payload(byId(responses, 4));
  assert.equal(quote.price, 72000);
  assert.equal(quote.market, 'KOSPI');
  assert.equal(quote.staleWarning, undefined);
  assert.equal(quote.haltWarning, undefined);
  assert.ok(quote.asOf && quote.source);
  const history = payload(byId(responses, 5));
  assert.equal(history.streak.direction, 'up');
  assert.equal(history.streak.days, 2);
  const plan = payload(byId(responses, 6));
  assert.equal(plan.currentPrice, 72000);
  assert.ok(plan.plan);
  // 낡은 .KS 잔재 대신 신선한 .KQ 가 선택되어야 한다
  const kosdaq = payload(byId(responses, 7));
  assert.equal(kosdaq.market, 'KOSDAQ');
  assert.equal(kosdaq.price, 180000);
  assert.equal(kosdaq.staleWarning, undefined);
  // 거래정지 의심 — 경고 + 매매 계획 거부
  const halted = payload(byId(responses, 8));
  assert.match(halted.haltWarning ?? '', /거래정지 가능성/);
  const haltedPlan = payload(byId(responses, 9));
  assert.equal(haltedPlan.feasible, false);
  assert.match(haltedPlan.reason, /거래정지/);
  // 사전계산 파일 서빙 (저장소 실재 파일) — ENOENT 대신 데이터 또는 안내가 와야 한다
  const stats = payload(byId(responses, 10));
  assert.ok(stats.horizons || stats.error);
  const universe = payload(byId(responses, 11));
  assert.ok(Array.isArray(universe.symbols) || universe.error);
  assert.equal(byId(responses, 12).result.isError, true);
  assert.match(byId(responses, 12).result.content[0].text, /6자리/);
  assert.equal(byId(responses, 13).result.isError, true);
  assert.match(byId(responses, 13).result.content[0].text, /unknown tool/);
  assert.equal(byId(responses, 14).error.code, -32601);
});
