#!/usr/bin/env node
/**
 * trusted-ceo-agent Naver News MCP server (stdio, zero-dependency).
 *
 * 네이버 뉴스 검색 OpenAPI 읽기 전용 프록시 — 기업 기사와 외부 평판 신호를 에이전트
 * 도구로 노출한다. CEO 리스크 분석에서 정량 데이터(DART/ECOS/내부 CSV)로 설명되지
 * 않는 투자유치, 제휴, 규제, 장애, 보안, 채용, 구조조정 신호를 확인하는 용도다.
 *
 * env: NAVER_CLIENT_ID, NAVER_CLIENT_SECRET (없으면 상위 .env 폴백 — client.mjs 참조)
 */
import { CLIENT_ID, CLIENT_SECRET, buildCompanyQuery, searchCompanyNews } from '../naver/client.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'trusted-ceo-agent-news';
const SERVER_VERSION = '0.1.0';

const DEFAULT_RISK_KEYWORDS = ['규제', '제재', '장애', '보안', '투자유치', '구조조정', '제휴'];

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
    name: 'news_search_company',
    description: '기업명 기준 네이버 뉴스 검색 — 기사 제목/요약/링크/발행일 메타데이터만 반환한다. 투자유치, 제휴, 채용, 사업 확장 등 정성 신호를 CEO 브리핑에 결합할 때 사용.',
    inputSchema: {
      type: 'object',
      properties: {
        company: { type: 'string', description: '검색할 기업명 (예: PFCT)' },
        keywords: { type: 'array', items: { type: 'string' }, description: '선택 검색 키워드 목록' },
        display: { type: 'integer', description: '결과 수 (1~100, 기본 10)' },
        sort: { type: 'string', enum: ['date', 'sim'], description: 'date 최신순 / sim 정확도순 (기본 date)' },
      },
      required: ['company'],
    },
    run: ({ company, keywords, display, sort }) =>
      searchCompanyNews({ company, keywords: parseKeywords(keywords), display: limitDisplay(display), sort }),
  },
  {
    name: 'news_search_risk',
    description: '기업명 + 리스크 키워드로 네이버 뉴스 검색 — 규제·제재·장애·보안·투자유치·구조조정·제휴 같은 외부 신호를 빠르게 확인한다. keywords 를 주지 않으면 기본 리스크 키워드를 사용하며, 키워드는 1개씩 개별 검색해 URL 기준으로 병합한다(여러 키워드 AND 결합은 0건이 되기 쉬움).',
    inputSchema: {
      type: 'object',
      properties: {
        company: { type: 'string', description: '검색할 기업명 (예: PFCT)' },
        keywords: { type: 'array', items: { type: 'string' }, description: '리스크 키워드 목록. 생략 시 기본 키워드 사용' },
        display: { type: 'integer', description: '키워드당 결과 수 (1~100, 기본 10)' },
        sort: { type: 'string', enum: ['date', 'sim'], description: 'date 최신순 / sim 정확도순 (기본 date)' },
      },
      required: ['company'],
    },
    run: async ({ company, keywords, display, sort }) => {
      const kws = parseKeywords(keywords, DEFAULT_RISK_KEYWORDS);
      const perKeyword = [];
      for (const kw of kws) {
        perKeyword.push(await searchCompanyNews({
          company, keywords: [kw], display: limitDisplay(display), sort,
        }));
      }
      const seen = new Set();
      const items = [];
      for (const search of perKeyword) {
        for (const item of search.items ?? []) {
          const url = item.url || item.naverUrl || item.title;
          if (seen.has(url)) continue;
          seen.add(url);
          items.push(item);
        }
      }
      return {
        query: perKeyword.map((s) => s.query).join(' | '),
        keywords: kws,
        perKeyword: perKeyword.map((s) => ({ query: s.query, total: s.total, itemCount: s.items?.length ?? 0 })),
        total: items.length,
        items,
      };
    },
  },
  {
    name: 'news_status',
    description: '네이버 뉴스 검색 연결 상태 점검 — API 키 존재 여부와 기본 리스크 키워드를 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: CLIENT_ID && CLIENT_SECRET ? 'present' : 'missing (env NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 또는 상위 .env)',
      defaultRiskKeywords: DEFAULT_RISK_KEYWORDS,
      queryExample: buildCompanyQuery({ company: 'PFCT', keywords: ['핀테크', '투자유치'] }),
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
