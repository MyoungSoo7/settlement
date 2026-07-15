#!/usr/bin/env node
/**
 * kakaopay-invest-companion ECOS MCP server (stdio, zero-dependency).
 *
 * 한국은행 ECOS OpenAPI 읽기 전용 프록시 — 거시 지표(금리·환율·물가)를 에이전트
 * 도구로 노출한다. 초보 투자자 응대에서 하락·불안의 원인이 '시장 전체 요인'인지
 * '종목 고유 요인'인지 분리하는 용도: 금리 급변 → 성장주 전반 조정, 환율 급변 →
 * 수출입주 영향, CPI → 소비주 영향. (ECOS 는 공개 read-only 통계 시스템 — 쓰기 API 없음)
 *
 * env: ECOS_API_KEY (없으면 상위 .env 폴백 — client.mjs 참조)
 */
import { INDICATORS, fetchIndicator, statisticSearch, keyStatistics, API_KEY } from '../ecos/client.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'invest-companion-ecos';
const SERVER_VERSION = '0.1.0';

// 시세 축(source/asOf)과 대칭 — 모든 응답에 "언제·어디서" 메타를 강제해
// trust-explainer 의 근거(출처·시점) 인용을 데이터 계층이 뒷받침한다.
const ECOS_SOURCE = '한국은행 ECOS OpenAPI (https://ecos.bok.or.kr)';
const withMeta = data => ({
  source: ECOS_SOURCE,
  retrievedAt: new Date().toISOString(),
  ...(Array.isArray(data) ? { count: data.length, rows: data } : data),
});

const TOOLS = [
  {
    name: 'ecos_indicator',
    description: '핵심 거시 지표 시계열 — BASE_RATE(기준금리) / TREASURY_3Y(국고채3년) / USD_KRW(원달러환율) / CPI(소비자물가). 최근 N개월 관측치 + 최신값 + 구간 변화량을 반환. 보유 종목 하락의 원인이 시장 전체 요인(금리·환율 급변)인지 종목 고유 요인인지 분리할 때, 매수 전 시장 환경 점검에 사용.',
    inputSchema: {
      type: 'object',
      properties: {
        code: { type: 'string', enum: ['BASE_RATE', 'TREASURY_3Y', 'USD_KRW', 'CPI'] },
        months_back: { type: 'integer', description: '조회 기간 개월 수 (기본 13 — 전년 동월 비교 가능)' },
      },
      required: ['code'],
    },
    run: async ({ code, months_back }) =>
      withMeta(await fetchIndicator(code, { monthsBack: Math.max(1, Math.min(Number(months_back ?? 13), 120)) })),
  },
  {
    name: 'ecos_series',
    description: 'ECOS 임의 통계 조회 (StatisticSearch 원시 호출) — 카탈로그 밖 지표가 필요할 때. stat_code/item_code 는 ecos.bok.or.kr 통계코드검색에서 확인. cycle D 는 날짜 yyyyMMdd, M 은 yyyyMM. 핵심 4개 지표는 ecos_indicator 가 더 간단하다.',
    inputSchema: {
      type: 'object',
      properties: {
        stat_code: { type: 'string', description: 'ECOS 통계표 코드 (예: 722Y001)' },
        item_code: { type: 'string', description: '통계 항목 코드 (예: 0101000)' },
        cycle: { type: 'string', enum: ['D', 'M', 'Q', 'A'], description: 'D일별 M월별 Q분기 A연간' },
        start: { type: 'string', description: '시작 (cycle 포맷: 20250101 / 202501 / 2025Q1 / 2025)' },
        end: { type: 'string', description: '종료 (cycle 포맷 동일)' },
      },
      required: ['stat_code', 'item_code', 'cycle', 'start', 'end'],
    },
    run: async ({ stat_code, item_code, cycle, start, end }) =>
      withMeta(await statisticSearch({ statCode: stat_code, itemCode: item_code, cycle, start, end })),
  },
  {
    name: 'ecos_key_stats',
    description: '한국은행 100대 통계지표 스냅숏 (KeyStatisticList) — GDP·고용·금리·환율·물가 등 거시 환경을 한 번에 훑을 때. 초보 투자자에게 "지금 시장이 어떤 국면인지" 설명하는 브리핑용. 개별 지표 시계열이 필요하면 ecos_indicator / ecos_series 사용.',
    inputSchema: { type: 'object', properties: {} },
    run: async () => withMeta(await keyStatistics()),
  },
  {
    name: 'ecos_status',
    description: 'ECOS 연결 상태 점검 — API 키 존재 여부와 카탈로그 지표 목록을 반환. 다른 도구가 실패할 때 원인 분리용.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: API_KEY ? 'present' : 'missing (env ECOS_API_KEY 또는 상위 .env)',
      catalog: Object.fromEntries(
        Object.entries(INDICATORS).map(([k, v]) => [k, `${v.name} (${v.statCode}/${v.itemCode}, ${v.cycle})`])),
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
