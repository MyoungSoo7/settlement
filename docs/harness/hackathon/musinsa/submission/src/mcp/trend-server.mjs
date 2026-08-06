#!/usr/bin/env node
/**
 * fashion-first Trend MCP server (stdio, zero-dependency).
 *
 * 트렌드 축 — "이 브랜드/아이템이 뜨는 중인가, 식는 중인가", "한 철 유행인가
 * 지속 트렌드인가" 에 시계열 근거로 답한다. 기본 백엔드는 뉴스 언급량 히스토그램
 * (키 2종만으로 동작)이고, 네이버 데이터랩 권한이 있으면 쇼핑인사이트 실클릭
 * 시계열도 함께 쓸 수 있다(datalab_shopping_trend).
 *
 * env: NAVER_CLIENT_ID, NAVER_CLIENT_SECRET (없으면 상위 .env 폴백)
 */
import { hasKeys } from '../naver/core.mjs';
import { buzzTrend, compareBuzz, datalabShoppingTrend } from '../naver/trend.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'fashion-first-trend';
const SERVER_VERSION = '0.1.0';

const TOOLS = [
  {
    name: 'buzz_trend',
    description: '브랜드/아이템 키워드의 월별 언론 언급량 시계열(최대 24개월)과 방향 판정(rising/flat/falling/rising-from-zero)을 반환한다. 최근 3개월 vs 직전 3개월 비율 기준. 언급량은 수요의 프록시이며, coverageComplete 와 미상(null) 달 표시로 수집 한계를 정직하게 동봉한다.',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string', description: '트렌드를 볼 키워드 (예: "발레코어", "쿠어")' },
        months: { type: 'integer', description: '조회 기간 개월 수 (3~24, 기본 12)' },
      },
      required: ['keyword'],
    },
    run: ({ keyword, months }) => buzzTrend({ keyword, months }),
  },
  {
    name: 'trend_compare',
    description: '키워드 2~5개의 버즈 트렌드를 비교해 성장 방향·최근 언급량 순으로 랭킹한다 — "A 브랜드 vs B 브랜드 중 어디가 상승세인가", "이 스타일 계열에서 뭐가 뜨나" 판정용.',
    inputSchema: {
      type: 'object',
      properties: {
        keywords: { type: 'array', items: { type: 'string' }, description: '비교할 키워드 2~5개' },
        months: { type: 'integer', description: '조회 기간 개월 수 (3~24, 기본 12)' },
      },
      required: ['keywords'],
    },
    run: ({ keywords, months }) => compareBuzz({ keywords, months }),
  },
  {
    name: 'datalab_shopping_trend',
    description: '네이버 데이터랩 쇼핑인사이트 — 패션의류 카테고리 내 키워드의 실제 클릭 트렌드 시계열(상대지수). 앱에 데이터랩 API 권한이 등록된 경우에만 동작하며, 미등록이면 등록 안내와 함께 buzz_trend 대체를 권한다. category 기본값 50000000(패션의류), 50000001(패션잡화).',
    inputSchema: {
      type: 'object',
      properties: {
        keywords: { type: 'array', items: { type: 'string' }, description: '키워드 1~5개' },
        months: { type: 'integer', description: '조회 기간 개월 수 (1~24, 기본 12)' },
        timeUnit: { type: 'string', enum: ['date', 'week', 'month'], description: '집계 단위 (기본 month)' },
        category: { type: 'string', description: '카테고리 코드 (기본 50000000 패션의류)' },
      },
      required: ['keywords'],
    },
    run: ({ keywords, months, timeUnit, category }) => datalabShoppingTrend({ keywords, months, timeUnit, category }),
  },
  {
    name: 'trend_status',
    description: '트렌드 축 연결 상태 점검 — API 키 존재 여부, 백엔드 구성, 판정 기준을 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: hasKeys() ? 'present' : 'missing (env NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 또는 상위 .env)',
      primaryBackend: '뉴스 언급량 월별 히스토그램 (뉴스 검색 API — 키 2종만으로 동작)',
      optionalBackend: '데이터랩 쇼핑인사이트 (앱에 데이터랩 API 권한 추가 시 datalab_shopping_trend 활성)',
      directionRule: '최근 3개월 합 / 직전 3개월 합 ≥ 1.3 → rising, ≤ 0.7 → falling, 그 외 flat',
      tools: TOOLS.map((t) => t.name),
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
