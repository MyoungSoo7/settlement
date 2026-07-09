#!/usr/bin/env node
/**
 * invest-companion Price MCP server (stdio, zero-dependency).
 *
 * KOSPI/KOSDAQ 시세 읽기 전용 축 — "지금 얼마인데?", "사흘 연속 오르는 중인가?"
 * (추격매수 규칙 판정), "52주 어디쯤인가?" 에 답한다. 키 불필요(데모용 공개
 * 어댑터 — Yahoo Finance, 지연 시세 가능). 실서비스에서는 price/client.mjs 만
 * 증권사 사내 시세 API 로 교체하면 도구 계약은 그대로 유지된다.
 *
 * stockCode(6자리)는 DART `dart_corp_search` 결과와 그대로 조인된다.
 */
import { history, quote } from '../price/client.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const SERVER_NAME = 'invest-companion-price';
const SERVER_VERSION = '0.1.0';

const TOOLS = [
  {
    name: 'price_quote',
    description: 'KRX 6자리 종목코드로 현재가·전일대비·52주 고저를 조회한다 (KOSPI→KOSDAQ 자동 해석, 지연 시세 가능). 기업명만 알면 먼저 dart_corp_search 로 stockCode 를 얻어라. "싸다/비싸다" 단정의 근거가 아니라 사실 확인용이다.',
    inputSchema: {
      type: 'object',
      properties: {
        stockCode: { type: 'string', description: 'KRX 6자리 종목코드 (예: 005930)' },
      },
      required: ['stockCode'],
    },
    run: ({ stockCode }) => quote(stockCode),
  },
  {
    name: 'price_history',
    description: '최근 N거래일 종가 시계열 + 연속 상승/하락 일수(streak)를 반환한다. buy-companion 의 "3거래일 연속 상승 중 신규 매수 금지" 규칙과 sell-companion 의 "당신 종목만 빠진 게 아닌지" 판정에 사용.',
    inputSchema: {
      type: 'object',
      properties: {
        stockCode: { type: 'string', description: 'KRX 6자리 종목코드 (예: 005930)' },
        days: { type: 'integer', description: '조회할 거래일 수 (2~90, 기본 20)' },
      },
      required: ['stockCode'],
    },
    run: ({ stockCode, days }) => history(stockCode, { days }),
  },
  {
    name: 'price_status',
    description: '시세 축 연결 상태 점검 — 데이터 소스와 지연 시세 고지를 반환한다.',
    inputSchema: { type: 'object', properties: {} },
    run: () => ({
      apiKey: 'not-required',
      source: 'Yahoo Finance chart API — 데모용 공개 어댑터, 지연 시세 가능',
      productionNote: '실서비스에서는 price/client.mjs 를 증권사 사내 시세 API 로 교체 (도구 계약 유지)',
      symbolRule: 'KRX 6자리 코드 → .KS(KOSPI) 우선, 실패 시 .KQ(KOSDAQ)',
    }),
  },
];

startJsonRpcServer({ serverName: SERVER_NAME, serverVersion: SERVER_VERSION, tools: TOOLS });
