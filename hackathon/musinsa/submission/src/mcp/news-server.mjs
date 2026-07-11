#!/usr/bin/env node
/**
 * fashion-first News MCP server (stdio, zero-dependency).
 *
 * 브랜드 평판·기회 신호 축 — "이 브랜드 요즘 논란 있나요?"(리스크),
 * "콜라보·팝업·신규 입점 소식 있나요?"(기회) 에 실보도 메타데이터로 답한다.
 * 기사 본문 미수집(제목/요약/링크/발행일만 — 저작권). 뉴스가 없으면
 * "없다고 말할 근거"가 된다.
 *
 * env: NAVER_CLIENT_ID, NAVER_CLIENT_SECRET (없으면 상위 .env 폴백)
 */
import { hasKeys } from '../naver/core.mjs';
import { searchMerged, searchNews } from '../naver/news.mjs';
import { CATEGORIES, brandRecallCheck, hasKey as hasRecallKey, searchRecalls } from '../recall/client.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'fashion-first-news';
const SERVER_VERSION = '0.1.0';

// 패션 소비자가 구매 전 놓치면 손해가 큰 리스크 계열
const DEFAULT_RISK_KEYWORDS = ['가품', '리콜', '불매', '논란', '소송', '폐업'];
// 팬이라면 놓치기 아까운 기회 계열
const DEFAULT_OPPORTUNITY_KEYWORDS = ['콜라보', '팝업', '한정판', '신제품', '입점'];

function parseKeywords(value, fallback = []) {
  if (Array.isArray(value)) return value.map((v) => String(v).trim()).filter(Boolean);
  if (typeof value === 'string') return value.split(/[,\s]+/).map((v) => v.trim()).filter(Boolean);
  return fallback;
}

function limitDisplay(value) {
  const n = Number(value ?? 10);
  if (!Number.isInteger(n)) return 10;
  return Math.max(1, Math.min(n, 100));
}

const TOOLS = [
  {
    name: 'fashion_news',
    description: '브랜드/키워드 기준 뉴스 검색 — 기사 제목/요약/링크/발행일 메타데이터만 반환한다. "이 브랜드 최근 소식이 뭔가", "이 유행이 언론에 어떻게 다뤄지나"의 1차 확인. 뉴스가 없으면 없다고 말할 근거가 된다.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: '검색어 (예: "마르디 메크르디")' },
        display: { type: 'integer', description: '결과 수 (1~100, 기본 10)' },
        sort: { type: 'string', enum: ['date', 'sim'], description: 'date 최신순 / sim 정확도순 (기본 date)' },
      },
      required: ['query'],
    },
    run: ({ query, display, sort }) => searchNews({ query, display: limitDisplay(display), sort }),
  },
  {
    name: 'brand_risk_scan',
    description: '브랜드 + 리스크 키워드(가품·리콜·불매·논란·소송·폐업)로 뉴스를 스캔한다 — 구매 전 평판 점검. 키워드는 1개씩 개별 검색 후 URL 병합(AND 결합은 0건이 되기 쉬움). "리스크 보도가 없음을 점검함"도 유효한 결과다. 기사 제목에 브랜드명이 있어도 내용이 다른 브랜드일 수 있으니 요약까지 확인해 인용할 것.',
    inputSchema: {
      type: 'object',
      properties: {
        brand: { type: 'string', description: '브랜드명' },
        keywords: { type: 'array', items: { type: 'string' }, description: '리스크 키워드 목록. 생략 시 기본 6종' },
        display: { type: 'integer', description: '키워드당 결과 수 (1~100, 기본 10)' },
      },
      required: ['brand'],
    },
    run: async ({ brand, keywords, display }) => {
      const name = String(brand ?? '').trim();
      if (!name) throw new Error('brand 는 비어 있을 수 없습니다');
      const kws = parseKeywords(keywords, DEFAULT_RISK_KEYWORDS);
      const merged = await searchMerged({ base: name, keywords: kws, display: limitDisplay(display) });
      return { brand: name, kind: 'risk', ...merged };
    },
  },
  {
    name: 'brand_opportunity_scan',
    description: '브랜드 + 기회 키워드(콜라보·팝업·한정판·신제품·입점)로 뉴스를 스캔한다 — 팬 관점의 "지금 챙길 소식". 키워드 1개씩 개별 검색 후 URL 병합.',
    inputSchema: {
      type: 'object',
      properties: {
        brand: { type: 'string', description: '브랜드명' },
        keywords: { type: 'array', items: { type: 'string' }, description: '기회 키워드 목록. 생략 시 기본 5종' },
        display: { type: 'integer', description: '키워드당 결과 수 (1~100, 기본 10)' },
      },
      required: ['brand'],
    },
    run: async ({ brand, keywords, display }) => {
      const name = String(brand ?? '').trim();
      if (!name) throw new Error('brand 는 비어 있을 수 없습니다');
      const kws = parseKeywords(keywords, DEFAULT_OPPORTUNITY_KEYWORDS);
      const merged = await searchMerged({ base: name, keywords: kws, display: limitDisplay(display) });
      return { brand: name, kind: 'opportunity', ...merged };
    },
  },
  {
    name: 'brand_recall_check',
    description: '브랜드/키워드를 공정거래위원회 소비자24 **공식 리콜 DB**에서 조회한다 — 뉴스 보도(프록시)가 아닌 정부 처분 기록. 상품명·사업자명 양쪽을 검색해 병합하며, "공식 리콜 이력 없음"도 유효한 결과다. 기본 카테고리는 화장품+공산품(의류·잡화 포함). brand_risk_scan(뉴스)과 교차 인용하면 평판 판정의 신뢰도가 올라간다.',
    inputSchema: {
      type: 'object',
      properties: {
        brand: { type: 'string', description: '브랜드명 또는 상품 키워드' },
        categories: { type: 'array', items: { type: 'string', enum: Object.keys(CATEGORIES) }, description: '조회 품목 (기본: ["화장품","공산품"])' },
        display: { type: 'integer', description: '검색당 결과 수 (1~100, 기본 10)' },
      },
      required: ['brand'],
    },
    run: ({ brand, categories, display }) => brandRecallCheck({ brand, categories: categories ?? undefined, perPage: limitDisplay(display) }),
  },
  {
    name: 'recall_latest',
    description: '품목별 최신 공식 리콜 공표 목록(소비자24) — "요즘 화장품 리콜 뭐 있었어?" 류 질문과 구매 전 품목 단위 점검용. 날짜 범위(yyyy-MM-dd) 필터 지원.',
    inputSchema: {
      type: 'object',
      properties: {
        category: { type: 'string', enum: Object.keys(CATEGORIES), description: '품목 (기본 화장품)' },
        fromDate: { type: 'string', description: '공표 시작일 yyyy-MM-dd (선택)' },
        toDate: { type: 'string', description: '공표 종료일 yyyy-MM-dd (선택)' },
        display: { type: 'integer', description: '결과 수 (1~100, 기본 20)' },
      },
    },
    run: ({ category, fromDate, toDate, display }) =>
      searchRecalls({ category: category ?? '화장품', fromDate, toDate, perPage: display ?? 20 }),
  },
  {
    name: 'news_status',
    description: '뉴스 축 연결 상태 점검 — API 키 존재 여부와 기본 리스크/기회 키워드를 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: hasKeys() ? 'present' : 'missing (env NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 또는 상위 .env)',
      recallApiKey: hasRecallKey() ? 'present (소비자24 반영 여부는 brand_recall_check 호출로 확인)' : 'missing (env DATA_GO_KR_API_KEY — data.go.kr 에서 공정거래위원회_리콜정보 활용신청)',
      recallCategories: Object.keys(CATEGORIES),
      defaultRiskKeywords: DEFAULT_RISK_KEYWORDS,
      defaultOpportunityKeywords: DEFAULT_OPPORTUNITY_KEYWORDS,
      tools: TOOLS.map((t) => t.name),
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
