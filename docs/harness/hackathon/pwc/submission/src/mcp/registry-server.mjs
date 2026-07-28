#!/usr/bin/env node
/**
 * trusted-ceo-agent Business Registry MCP server (stdio, zero-dependency).
 *
 * 국세청 사업자등록정보 진위확인/상태조회 API 읽기 전용 프록시.
 * 기업 분석 전에 "정확히 어느 법인/사업자를 보는지"를 확정하는 입력 게이트 용도다.
 *
 * env: DATA_GO_KR_API_KEY (없으면 상위 .env 폴백 — client.mjs 참조)
 */
import {
  API_KEY,
  businessAuthCheck,
  businessStatusCheck,
  companyIdentityGate,
  validateBusinessNumber,
} from '../registry/client.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'trusted-ceo-agent-registry';
const SERVER_VERSION = '0.1.0';

function asArray(value) {
  if (Array.isArray(value)) return value;
  if (typeof value === 'string') return value.split(/[,\s]+/).map((v) => v.trim()).filter(Boolean);
  return value == null ? [] : [value];
}

const TOOLS = [
  {
    name: 'business_number_validate',
    description: '사업자등록번호 로컬 검증 — 하이픈을 제거해 10자리 형식과 체크섬을 확인한다. API 키 없이도 동작하며, 외부 조회 전에 먼저 호출한다.',
    inputSchema: {
      type: 'object',
      properties: {
        b_no: { type: 'string', description: '사업자등록번호 (예: 124-81-00998)' },
      },
      required: ['b_no'],
    },
    run: ({ b_no }) => validateBusinessNumber(b_no),
  },
  {
    name: 'business_status_check',
    description: '국세청 사업자등록 상태조회 — 사업자번호만으로 계속/휴업/폐업/미등록 상태와 과세유형을 확인한다. DATA_GO_KR_API_KEY 필요.',
    inputSchema: {
      type: 'object',
      properties: {
        b_no: { description: '사업자등록번호 1개 또는 목록', oneOf: [{ type: 'string' }, { type: 'array', items: { type: 'string' } }] },
      },
      required: ['b_no'],
    },
    run: ({ b_no }) => businessStatusCheck({ businessNumbers: asArray(b_no) }),
  },
  {
    name: 'business_auth_check',
    description: '국세청 사업자등록 진위확인 — 사업자번호, 개업일자(YYYYMMDD), 대표자명 등을 국세청 등록정보와 대조한다. DATA_GO_KR_API_KEY 필요.',
    inputSchema: {
      type: 'object',
      properties: {
        b_no: { type: 'string', description: '사업자등록번호' },
        start_dt: { type: 'string', description: '개업일자 YYYYMMDD' },
        p_nm: { type: 'string', description: '대표자명' },
        b_nm: { type: 'string', description: '상호명/법인명 (선택)' },
        corp_no: { type: 'string', description: '법인등록번호 (선택)' },
        b_sector: { type: 'string', description: '업태 (선택)' },
        b_type: { type: 'string', description: '종목 (선택)' },
      },
      required: ['b_no', 'start_dt', 'p_nm'],
    },
    run: (args) => businessAuthCheck({ businesses: [args] }),
  },
  {
    name: 'company_identity_gate',
    description: '기업 분석 입력 게이트 — 기업명과 사업자등록번호를 필수로 받고, 로컬 체크섬 검증 후 가능한 경우 국세청 상태조회/진위확인을 수행한다. DART/뉴스 검색 기준 식별자도 함께 정리한다.',
    inputSchema: {
      type: 'object',
      properties: {
        company_name: { type: 'string', description: '분석 대상 기업명' },
        business_number: { type: 'string', description: '사업자등록번호' },
        representative_name: { type: 'string', description: '대표자명 (진위확인 선택)' },
        opening_date: { type: 'string', description: '개업일자 YYYYMMDD (진위확인 선택)' },
        stock_code: { type: 'string', description: '상장사 종목코드 6자리 (선택)' },
        dart_corp_code: { type: 'string', description: 'DART 고유번호 8자리 (선택)' },
        news_query_name: { type: 'string', description: '뉴스 검색에 사용할 기업명 별칭 (선택)' },
      },
      required: ['company_name', 'business_number'],
    },
    run: (args) => companyIdentityGate({
      companyName: args.company_name,
      businessNumber: args.business_number,
      representativeName: args.representative_name,
      openingDate: args.opening_date,
      stockCode: args.stock_code,
      dartCorpCode: args.dart_corp_code,
      newsQueryName: args.news_query_name,
    }),
  },
  {
    name: 'registry_status',
    description: '국세청 사업자등록정보 API 연결 상태 점검 — DATA_GO_KR_API_KEY 존재 여부와 제공 도구를 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: API_KEY ? 'present' : 'missing (env DATA_GO_KR_API_KEY 또는 상위 .env)',
      tools: ['business_number_validate', 'business_status_check', 'business_auth_check', 'company_identity_gate'],
      source: '국세청_사업자등록정보 진위확인 및 상태조회 서비스 (data.go.kr)',
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
