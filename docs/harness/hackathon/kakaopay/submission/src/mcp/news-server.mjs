#!/usr/bin/env node
/**
 * invest-companion Naver News MCP server (stdio, zero-dependency).
 *
 * 네이버 뉴스 검색 OpenAPI 읽기 전용 프록시 — 초보 투자자의 불안 4유형 중
 * "정보 부족"("이 뉴스가 악재인가요?", "왜 떨어지는 거예요?")에 답하기 위한 축.
 * 하락의 이유가 실제 뉴스로 확인되는 악재인지, 아니면 뉴스 없는 수급 변동인지를
 * 분리한다. 기사 본문은 수집하지 않는다 — 제목/요약/링크/발행일 메타데이터만.
 *
 * env: NAVER_CLIENT_ID, NAVER_CLIENT_SECRET (없으면 상위 .env 폴백 — client.mjs 참조)
 */
import { CLIENT_ID, CLIENT_SECRET, buildCompanyQuery, searchCompanyNews } from '../naver/client.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'invest-companion-news';
const SERVER_VERSION = '0.1.0';

// 시세 축(source/asOf)과 대칭 — "언제 수집한 검색 결과인지"를 응답에 강제한다.
// 뉴스는 소급 조회가 불가능하므로 retrievedAt 이 곧 이 결과의 유효 시점이다.
const withMeta = data => ({
  source: '네이버 뉴스 검색 OpenAPI (제목·요약·링크만, 본문 미수집)',
  retrievedAt: new Date().toISOString(),
  ...data,
});

// 초보 투자자가 놓치면 치명적인 악재 계열 우선 (지분 희석·신뢰 훼손·거래 제한)
const DEFAULT_RISK_KEYWORDS = ['유상증자', '횡령', '배임', '거래정지', '상장폐지', '실적', '소송'];

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
    description: '종목(기업명) 기준 네이버 뉴스 검색 — 기사 제목/요약/링크/발행일 메타데이터만 반환한다. "왜 떨어지는/오르는 거예요?"에 대해 실제 보도가 있는지 확인할 때 사용. 뉴스가 없으면 없다고 말할 근거가 된다.',
    inputSchema: {
      type: 'object',
      properties: {
        company: { type: 'string', description: '검색할 기업명 (예: 삼성전자)' },
        keywords: { type: 'array', items: { type: 'string' }, description: '선택 검색 키워드 목록 (예: ["실적"])' },
        display: { type: 'integer', description: '결과 수 (1~100, 기본 10)' },
        sort: { type: 'string', enum: ['date', 'sim'], description: 'date 최신순 / sim 정확도순 (기본 date)' },
      },
      required: ['company'],
    },
    run: async ({ company, keywords, display, sort }) =>
      withMeta(await searchCompanyNews({ company, keywords: parseKeywords(keywords), display: limitDisplay(display), sort })),
  },
  {
    name: 'news_search_risk',
    description: '종목(기업명) + 악재 키워드로 네이버 뉴스 검색 — 유상증자·횡령·배임·거래정지·상장폐지·실적·소송 같은 초보 투자자가 놓치기 쉬운 악재 신호를 확인한다. keywords 를 주지 않으면 기본 악재 키워드를 사용하며, 키워드는 1개씩 개별 검색해 URL 기준으로 병합한다(여러 키워드 AND 결합은 0건이 되기 쉬움). "악재가 없음을 점검함"도 유효한 결과다.',
    inputSchema: {
      type: 'object',
      properties: {
        company: { type: 'string', description: '검색할 기업명 (예: 삼성전자)' },
        keywords: { type: 'array', items: { type: 'string' }, description: '악재 키워드 목록. 생략 시 기본 키워드 사용' },
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
        await new Promise((resolve) => setTimeout(resolve, 120)); // 속도 제한 완화
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
      return withMeta({
        query: perKeyword.map((s) => s.query).join(' | '),
        keywords: kws,
        perKeyword: perKeyword.map((s) => ({ query: s.query, total: s.total, itemCount: s.items?.length ?? 0 })),
        total: items.length,
        items,
      });
    },
  },
  {
    name: 'news_status',
    description: '네이버 뉴스 검색 연결 상태 점검 — API 키 존재 여부와 기본 악재 키워드를 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: CLIENT_ID && CLIENT_SECRET ? 'present' : 'missing (env NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 또는 상위 .env)',
      defaultRiskKeywords: DEFAULT_RISK_KEYWORDS,
      queryExample: buildCompanyQuery({ company: '삼성전자', keywords: ['실적'] }),
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
