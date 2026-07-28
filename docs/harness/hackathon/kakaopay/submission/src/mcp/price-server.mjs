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
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { history, quote } from '../price/client.mjs';
import { computeTradePlan } from '../common/trade-plan.mjs';
import { startJsonRpcServer } from './json-rpc-stdio.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const STATS_PATH = join(HERE, '..', 'data', 'stats', 'backtest-stats.json');
const UNIVERSE_PATH = join(HERE, '..', 'data', 'universe', 'krx-top.json');

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
    name: 'plan_trade',
    description: '현재가로 매매 기준가를 계산한다 — 3분할 매수 밴드(30%/30%/40%, 현재가/-5%/-10%), 손절 -7%(전량), 1차 익절 +20%(절반). budget(원)을 주면 밴드별 수량까지, 없으면 주당 가격 레벨만. 가격 예측이 아니라 진입·청산 규칙이며 KRX 호가단위로 내림한다. "얼마에 사고 얼마에 팔지" 질문에 이 도구의 결과로 답한다.',
    inputSchema: {
      type: 'object',
      properties: {
        stockCode: { type: 'string', description: 'KRX 6자리 종목코드 (예: 005930)' },
        budget: { type: 'integer', description: '이 종목에 배정할 총 예산 (원, 선택 — 없으면 가격 레벨만 반환)' },
      },
      required: ['stockCode'],
    },
    run: async ({ stockCode, budget }) => {
      const q = await quote(stockCode);
      if (q.staleWarning || q.haltWarning) {
        // 낡은 시세·거래정지 의심 위에 매매 계획을 세우면 초보자에게 틀린 가격이 나간다 — 계산을 거부한다
        return {
          stockCode: q.stockCode,
          name: q.name,
          feasible: false,
          reason: q.staleWarning ?? q.haltWarning,
          lastKnown: { price: q.price, asOf: q.asOf },
        };
      }
      return {
        stockCode: q.stockCode,
        name: q.name,
        market: q.market,
        currentPrice: q.price,
        asOf: q.asOf,
        plan: computeTradePlan({
          price: q.price,
          budget: budget === undefined || budget === null ? undefined : Number(budget),
        }),
        source: q.source,
      };
    },
  },
  {
    name: 'backtest_stats',
    description: 'KRX 대형주 유니버스(66종목, 10년)의 보유기간별(월/분기/연) 실측 승률·수익 분포를 반환한다 — "오를 확률"을 지어내는 대신 인용할 정직한 기저 통계. 승률은 51~56% 수준이고 최악 5% 구간 손실(p5Return)이 함께 있다. 종목 추천 응답에는 반드시 이 기대치와 한계(생존 편향·배당 제외)를 병기하라. 재계산: node src/bin/backtest.mjs',
    inputSchema: { type: 'object', properties: {} },
    run: () => {
      if (!existsSync(STATS_PATH)) {
        return { error: '사전계산 파일이 없습니다 — node src/bin/backtest.mjs 를 먼저 실행하세요 (네트워크 필요, 1~2분)' };
      }
      return JSON.parse(readFileSync(STATS_PATH, 'utf8'));
    },
  },
  {
    name: 'universe_list',
    description: '스크리닝 출발점이 되는 KRX 시가총액 상위 데모 유니버스(66종목, 코드·이름)를 반환한다. periodic-picks 스킬의 후보 풀. asOf 와 선정 기준·생존 편향 주의가 함께 온다 — 이 목록 밖 종목도 사용자가 원하면 같은 규칙으로 점검 가능.',
    inputSchema: { type: 'object', properties: {} },
    run: () => {
      if (!existsSync(UNIVERSE_PATH)) {
        return { error: '유니버스 파일이 없습니다 — src/data/universe/krx-top.json 이 패키지에 포함되어야 합니다 (저장소에서 복원하세요)' };
      }
      return JSON.parse(readFileSync(UNIVERSE_PATH, 'utf8'));
    },
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
