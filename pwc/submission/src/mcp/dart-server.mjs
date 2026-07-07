#!/usr/bin/env node
/**
 * trusted-ceo-agent DART MCP server (stdio, zero-dependency).
 *
 * DART OpenAPI(전자공시) 읽기 전용 프록시 — 기업 고유번호 검색 → 기업개황 →
 * 공시목록 → 재무제표(요약/전체) 를 에이전트 도구로 노출한다.
 * 쓰기 API 는 존재하지 않는다 (DART 자체가 read-only 공시 시스템).
 *
 * env: DART_API_KEY (없으면 상위 .env 폴백 — client.mjs 참조)
 */
import { createInterface } from 'node:readline';
import { company, disclosures, financialSummary, financialFull, API_KEY } from '../dart/client.mjs';
import { searchCorp, loadCorpCodes } from '../dart/corp-codes.mjs';

const ymd = d => d.toISOString().slice(0, 10).replaceAll('-', '');
const daysAgo = n => new Date(Date.now() - n * 86_400_000);

const TOOLS = [
  {
    name: 'dart_corp_search',
    description: 'DART 기업 검색 — 기업명 일부/종목코드(6자리)/고유번호(8자리)로 corp_code 를 찾는다. 모든 DART 도구의 진입점 (다른 도구는 corp_code 8자리를 요구). 최초 호출 시 전체 고유번호 파일을 내려받아 7일 캐시한다 (상장사만).',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string', description: '기업명 일부 또는 종목코드/고유번호' },
        limit: { type: 'integer', description: '최대 결과 수 (기본 10)' },
      },
      required: ['keyword'],
    },
    run: ({ keyword, limit }) => searchCorp(keyword, { limit: Number(limit ?? 10) }),
  },
  {
    name: 'dart_company',
    description: '기업개황 — 대표자/설립일/상장일/업종코드/주소 등 기본 정보. (DART company.json)',
    inputSchema: {
      type: 'object',
      properties: { corp_code: { type: 'string', description: 'DART 고유번호 8자리' } },
      required: ['corp_code'],
    },
    run: ({ corp_code }) => company(corp_code),
  },
  {
    name: 'dart_disclosures',
    description: '공시 목록 — 최근 N일(기본 30)의 공시 접수 내역. 리스크 신호 탐지용: 정정공시 반복, 감사보고서 지연, 주요사항보고(B) 급증 등은 그 자체가 행간 신호다. pblntf_ty: A정기 B주요사항 C발행 D지분 E기타 F외부감사. (DART list.json)',
    inputSchema: {
      type: 'object',
      properties: {
        corp_code: { type: 'string', description: 'DART 고유번호 8자리' },
        days: { type: 'integer', description: '조회 기간 일수 (기본 30, 최대 365)' },
        pblntf_ty: { type: 'string', enum: ['A', 'B', 'C', 'D', 'E', 'F'] },
        page_count: { type: 'integer', description: '결과 수 (기본 50, 최대 100)' },
      },
      required: ['corp_code'],
    },
    run: ({ corp_code, days, pblntf_ty, page_count }) => {
      const n = Math.max(1, Math.min(Number(days ?? 30), 365));
      return disclosures({
        corpCode: corp_code,
        bgnDe: ymd(daysAgo(n)), endDe: ymd(new Date()),
        pblntfTy: pblntf_ty,
        pageCount: Math.min(Number(page_count ?? 50), 100),
      });
    },
  },
  {
    name: 'dart_financial_summary',
    description: '단일회사 주요계정 요약 — 유동자산/부채/자본/매출/영업이익/당기순이익 등 핵심 계정만 (당기·전기·전전기 비교 포함). reprt_code: 11011 사업보고서(연간) / 11012 반기 / 11013 1분기 / 11014 3분기. status 013 이면 해당 연도/보고서 미공시. (DART fnlttSinglAcnt.json)',
    inputSchema: {
      type: 'object',
      properties: {
        corp_code: { type: 'string', description: 'DART 고유번호 8자리' },
        year: { type: 'integer', description: '사업연도 (예: 2024)' },
        reprt_code: { type: 'string', enum: ['11011', '11012', '11013', '11014'] },
      },
      required: ['corp_code', 'year'],
    },
    run: ({ corp_code, year, reprt_code }) =>
      financialSummary({ corpCode: corp_code, year, reprtCode: reprt_code ?? '11011' }),
  },
  {
    name: 'dart_financial_full',
    description: '단일회사 전체 재무제표 — 전 계정과목 (재무상태표/손익/포괄손익/현금흐름/자본변동). 원가 배분·현금흐름 병목 분석처럼 세부 계정이 필요할 때 사용 (응답이 큼 — 요약이 충분하면 dart_financial_summary 먼저). fs_div: CFS 연결 / OFS 별도. (DART fnlttSinglAcntAll.json)',
    inputSchema: {
      type: 'object',
      properties: {
        corp_code: { type: 'string', description: 'DART 고유번호 8자리' },
        year: { type: 'integer', description: '사업연도' },
        reprt_code: { type: 'string', enum: ['11011', '11012', '11013', '11014'] },
        fs_div: { type: 'string', enum: ['CFS', 'OFS'] },
      },
      required: ['corp_code', 'year'],
    },
    run: ({ corp_code, year, reprt_code, fs_div }) =>
      financialFull({ corpCode: corp_code, year, reprtCode: reprt_code ?? '11011', fsDiv: fs_div ?? 'CFS' }),
  },
  {
    name: 'dart_status',
    description: 'DART 연결 상태 점검 — API 키 존재 여부와 고유번호 캐시 상태를 반환. 다른 도구가 실패할 때 원인 분리용.',
    inputSchema: { type: 'object', properties: {} },
    run: async () => {
      const hasKey = Boolean(API_KEY);
      let cache = null;
      if (hasKey) {
        try {
          const c = await loadCorpCodes();
          cache = { fetchedAt: c.fetchedAt, listedCount: c.listedCount, totalCount: c.totalCount };
        } catch (e) { cache = { error: e.message }; }
      }
      return { apiKey: hasKey ? 'present' : 'missing (env DART_API_KEY 또는 상위 .env)', corpCodeCache: cache };
    },
  },
];

// ── JSON-RPC over stdio (newline-delimited) ──────────────────────────────────
function send(msg) { process.stdout.write(JSON.stringify(msg) + '\n'); }

function toolResult(id, payload, isError = false) {
  send({
    jsonrpc: '2.0', id,
    result: {
      content: [{ type: 'text', text: typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2) }],
      isError,
    },
  });
}

const rl = createInterface({ input: process.stdin, terminal: false });
rl.on('line', async (line) => {
  line = line.trim();
  if (!line) return;
  let req;
  try { req = JSON.parse(line); } catch { return; }
  const { id, method, params } = req;

  try {
    if (method === 'initialize') {
      send({
        jsonrpc: '2.0', id,
        result: {
          protocolVersion: params?.protocolVersion ?? '2025-03-26',
          capabilities: { tools: {} },
          serverInfo: { name: 'trusted-ceo-agent-dart', version: '0.1.0' },
        },
      });
    } else if (method === 'notifications/initialized' || method?.startsWith('notifications/')) {
      // no response for notifications
    } else if (method === 'tools/list') {
      send({
        jsonrpc: '2.0', id,
        result: { tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) },
      });
    } else if (method === 'tools/call') {
      const tool = TOOLS.find(t => t.name === params?.name);
      if (!tool) return toolResult(id, `unknown tool: ${params?.name}`, true);
      try {
        toolResult(id, await tool.run(params?.arguments ?? {}));
      } catch (e) {
        toolResult(id, `tool error: ${e.message}`, true);
      }
    } else if (id !== undefined) {
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } });
    }
  } catch (e) {
    if (id !== undefined) send({ jsonrpc: '2.0', id, error: { code: -32603, message: e.message } });
  }
});
