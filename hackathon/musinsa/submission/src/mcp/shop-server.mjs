#!/usr/bin/env node
/**
 * fashion-first Shop MCP server (stdio, zero-dependency).
 *
 * 상품·가격·브랜드 실체 확인 축 — "이 세일가가 진짜 싼 건가?", "이 브랜드가
 * 어떤 카테고리를 얼마에 파는 브랜드인가?" 에 공개 유통 데이터로 답한다.
 * 데모는 네이버 쇼핑 검색이 백엔드이며, 실서비스에서는 naver/shop.mjs 한 파일만
 * 무신사 내부 상품 검색 API 로 교체하면 도구 계약이 유지된다.
 *
 * env: NAVER_CLIENT_ID, NAVER_CLIENT_SECRET (없으면 상위 .env 폴백 — core.mjs 참조)
 */
import { hasKeys } from '../naver/core.mjs';
import { brandSnapshot, priceBand, searchShop } from '../naver/shop.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'fashion-first-shop';
const SERVER_VERSION = '0.1.0';

const TOOLS = [
  {
    name: 'shop_search',
    description: '상품 키워드로 유통 시장 검색 — 상품명/최저가/브랜드/판매몰/카테고리 메타데이터를 반환한다. "이 옷 지금 시장에 얼마에 풀려 있나"의 1차 사실 확인. sort: sim 정확도 | date 최신 | asc 저가 | dsc 고가.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: '검색어 (예: "마르디 메크르디 티셔츠")' },
        display: { type: 'integer', description: '결과 수 (1~100, 기본 20)' },
        sort: { type: 'string', enum: ['sim', 'date', 'asc', 'dsc'], description: '정렬 (기본 sim)' },
        excludeUsed: { type: 'boolean', description: 'true 면 중고/렌탈/해외직구 제외 (기본 true)' },
      },
      required: ['query'],
    },
    run: ({ query, display, sort, excludeUsed }) =>
      searchShop({ query, display, sort, exclude: excludeUsed === false ? undefined : 'used:rental:cbshop' }),
  },
  {
    name: 'price_band',
    description: '상품 키워드의 시장 가격 분포(최저/25%/중앙값/75%/최고)를 계산한다. referencePrice(지금 보고 있는 가격)를 주면 그 가격이 분포의 몇 퍼센타일인지, 중앙값 대비 몇 % 인지 판정한다 — "이 세일이 실제로 싼 건지"의 근거. 신품 위주 표본.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: '가격 분포를 볼 상품 검색어' },
        referencePrice: { type: 'integer', description: '비교할 기준 가격 (원). 예: 무신사 세일가' },
        sample: { type: 'integer', description: '표본 크기 (20~200, 기본 100)' },
      },
      required: ['query'],
    },
    run: ({ query, referencePrice, sample }) => priceBand({ query, referencePrice, sample }),
  },
  {
    name: 'brand_snapshot',
    description: '브랜드명 하나로 시장 존재감 스냅샷 — 유통 상품 수, 주력 카테고리 분포, 카테고리별 중앙값 가격대, 대표 상품 표본을 반환한다. 처음 듣는 브랜드의 실체("뭘 만들고 얼마 받는 브랜드인가")를 확인하는 첫 도구. brandFieldMatchSharePct 가 낮으면 동명 잡화 혼입 가능성에 주의.',
    inputSchema: {
      type: 'object',
      properties: {
        brand: { type: 'string', description: '브랜드명 (예: "쿠어", "마르디 메크르디")' },
        sample: { type: 'integer', description: '표본 크기 (20~200, 기본 100)' },
      },
      required: ['brand'],
    },
    run: ({ brand, sample }) => brandSnapshot({ brand, sample }),
  },
  {
    name: 'shop_status',
    description: '쇼핑 검색 연결 상태 점검 — API 키 존재 여부와 백엔드 설명을 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: hasKeys() ? 'present' : 'missing (env NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 또는 상위 .env)',
      backend: '네이버 쇼핑 검색 OpenAPI (데모용 공개 유통 데이터). 실서비스: naver/shop.mjs 를 무신사 내부 상품 검색 API 로 교체 — 도구 계약 유지.',
      tools: TOOLS.map((t) => t.name),
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
