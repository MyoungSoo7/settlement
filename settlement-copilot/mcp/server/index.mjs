#!/usr/bin/env node
/**
 * settlement-copilot MCP server (stdio, zero-dependency).
 *
 * 정산 서비스가 이미 노출하는 읽기 전용 API/메트릭만 프록시한다.
 * - 쓰기 API 는 어떤 것도 라우팅하지 않는다 (read-only by construction).
 * - 응답은 서버 측에서 PII 마스킹 후 에이전트에 전달한다.
 * - 금액은 JSON number 로 재해석하지 않고 원문 그대로(문자열 포함) 넘긴다.
 *
 * env:
 *   SETTLEMENT_BASE_URL (default http://localhost:8082)
 *   ORDER_BASE_URL      (default http://localhost:8088)
 *   COPILOT_ADMIN_TOKEN (선택 — admin API 용 Bearer JWT)
 *   INTERNAL_API_KEY    (선택 — order /internal/recon/* 용 X-Internal-Api-Key)
 */

import { createInterface } from 'node:readline';

const SETTLEMENT = (process.env.SETTLEMENT_BASE_URL ?? 'http://localhost:8082').replace(/\/$/, '');
const ORDER = (process.env.ORDER_BASE_URL ?? 'http://localhost:8088').replace(/\/$/, '');
const ADMIN_TOKEN = process.env.COPILOT_ADMIN_TOKEN ?? '';
const INTERNAL_KEY = process.env.INTERNAL_API_KEY ?? '';

// ── PII masking (키 이름 기반 — 금액 필드 오탐 방지) ─────────────────────────
const PII_KEY = /(account|acct|bank.*(no|num)|resident|ssn|card.*(no|num)|phone|계좌|주민|카드번호|연락처)/i;

function maskValue(v) {
  const s = String(v);
  if (s.length <= 4) return '****';
  return '*'.repeat(s.length - 4) + s.slice(-4);
}

function deepMask(node) {
  if (Array.isArray(node)) return node.map(deepMask);
  if (node && typeof node === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(node)) {
      out[k] = PII_KEY.test(k) && (typeof v === 'string' || typeof v === 'number')
        ? maskValue(v)
        : deepMask(v);
    }
    return out;
  }
  return node;
}

// ── HTTP (GET only — 이 서버는 구조적으로 읽기 전용) ─────────────────────────
async function getJson(base, path, { internal = false } = {}) {
  const headers = { Accept: 'application/json' };
  if (internal && INTERNAL_KEY) headers['X-Internal-Api-Key'] = INTERNAL_KEY;
  else if (ADMIN_TOKEN) headers['Authorization'] = `Bearer ${ADMIN_TOKEN}`;

  const res = await fetch(base + path, { headers, signal: AbortSignal.timeout(10_000) });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${base}${path} → HTTP ${res.status}: ${text.slice(0, 300)}`);
  }
  try { return deepMask(JSON.parse(text)); } catch { return text; }
}

async function getPrometheus(base, metricPrefixes) {
  const res = await fetch(base + '/actuator/prometheus', {
    headers: ADMIN_TOKEN ? { Authorization: `Bearer ${ADMIN_TOKEN}` } : {},
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new Error(`${base}/actuator/prometheus → HTTP ${res.status}`);
  const body = await res.text();
  const lines = body.split('\n').filter(l =>
    !l.startsWith('#') && metricPrefixes.some(p => l.startsWith(p)));
  return lines.map(l => {
    const sp = l.lastIndexOf(' ');
    return { metric: l.slice(0, sp), value: l.slice(sp + 1) };
  });
}

// ── settlement_simulate — 도메인 정책 미러 (SellerTier / HoldbackPolicy) ────
// 근거: settlement.domain.SellerTier (0.0350/0.0250/0.0200, T+7/T+3/T+1),
//       settlement.domain.HoldbackPolicy.forTier (0.30/30d, 0.10/14d, 0/0d)
// 금액은 KRW 정수 전제, BigInt + HALF_UP 라운딩 (float 사용 금지 — money-safety 규칙).
const TIERS = {
  NORMAL:    { feeBp: 350n, cycle: 'T+7 영업일', holdbackBp: 3000n, releaseDays: 30 },
  VIP:       { feeBp: 250n, cycle: 'T+3 영업일', holdbackBp: 1000n, releaseDays: 14 },
  STRATEGIC: { feeBp: 200n, cycle: 'T+1 영업일', holdbackBp: 0n,    releaseDays: 0 },
};

function mulBpHalfUp(amount, bp) { // amount * bp / 10000, HALF_UP
  return (amount * bp + 5000n) / 10000n;
}

function simulate({ amount, tier }) {
  const t = TIERS[String(tier ?? '').toUpperCase()];
  if (!t) throw new Error(`unknown tier: ${tier} (NORMAL|VIP|STRATEGIC)`);
  if (!/^\d+$/.test(String(amount))) throw new Error('amount 는 KRW 정수(원 단위)여야 합니다');
  const gross = BigInt(amount);
  const fee = mulBpHalfUp(gross, t.feeBp);
  const net = gross - fee;
  const holdback = mulBpHalfUp(net, t.holdbackBp);
  const immediate = net - holdback;
  return {
    input: { amount: gross.toString(), tier: String(tier).toUpperCase() },
    commissionRate: (Number(t.feeBp) / 10000).toFixed(4),
    fee: fee.toString(),
    netAmount: net.toString(),
    holdback: {
      rate: (Number(t.holdbackBp) / 10000).toFixed(2),
      amount: holdback.toString(),
      releaseDays: t.releaseDays,
      note: t.releaseDays === 0 ? '즉시 전액 정산' : `영업일 기준 ${t.releaseDays}일 후 해제`,
    },
    immediatePayout: immediate.toString(),
    settlementCycle: t.cycle,
    rounding: 'HALF_UP',
    basis: 'SellerTier + HoldbackPolicy.forTier (정산 시점 commission_rate 스냅샷 정책)',
  };
}

// ── Tool registry ────────────────────────────────────────────────────────────
const TOOLS = [
  {
    name: 'recon_run',
    description: '정산 일일 대사 실행/조회 — 해당 날짜의 결제/환불/정산 금액 일치 검증. matched=false 면 원장 불일치. (settlement /admin/reconciliation)',
    inputSchema: {
      type: 'object',
      properties: { date: { type: 'string', description: 'ISO date, e.g. 2026-07-06' } },
      required: ['date'],
    },
    run: ({ date }) => getJson(SETTLEMENT, `/admin/reconciliation?date=${encodeURIComponent(date)}`),
  },
  {
    name: 'order_recon_totals',
    description: 'order 원천 대사 합계 — CAPTURED 결제·COMPLETED 환불 합계. date 하나(일일) 또는 from/to(기간). recon_run 결과와 교차 검증용. (order /internal/recon)',
    inputSchema: {
      type: 'object',
      properties: {
        date: { type: 'string' }, from: { type: 'string' }, to: { type: 'string' },
      },
    },
    run: ({ date, from, to }) => {
      if (date) return getJson(ORDER, `/internal/recon/daily-totals?date=${encodeURIComponent(date)}`, { internal: true });
      if (from && to) return getJson(ORDER, `/internal/recon/period-totals?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { internal: true });
      throw new Error('date 또는 from+to 필요');
    },
  },
  {
    name: 'ledger_entries',
    description: '기간별 복식부기 원장 분개 조회 + 차/대 합계 요약(시산표). 불일치 시 ledger-invariants skill 기준으로 해석. (settlement /api/ledger/entries)',
    inputSchema: {
      type: 'object',
      properties: { from: { type: 'string' }, to: { type: 'string' } },
      required: ['from', 'to'],
    },
    run: async ({ from, to }) => {
      const entries = await getJson(SETTLEMENT,
        `/api/ledger/entries?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
      // best-effort 시산표: DEBIT/CREDIT 표시 필드와 amount 필드가 있으면 BigInt 로 합산
      let debit = 0n, credit = 0n, summable = true;
      if (Array.isArray(entries)) {
        for (const e of entries) {
          const side = String(e.entryType ?? e.type ?? e.side ?? '').toUpperCase();
          const amt = String(e.amount ?? '');
          if (!/^-?\d+(\.0+)?$/.test(amt) || (side !== 'DEBIT' && side !== 'CREDIT')) { summable = false; break; }
          const v = BigInt(amt.split('.')[0]);
          if (side === 'DEBIT') debit += v; else credit += v;
        }
      } else summable = false;
      return {
        count: Array.isArray(entries) ? entries.length : null,
        trialBalance: summable
          ? { debitTotal: debit.toString(), creditTotal: credit.toString(), balanced: debit === credit }
          : { note: '필드 구조상 서버측 합산 불가 — entries 를 직접 검증하세요' },
        entries,
      };
    },
  },
  {
    name: 'projection_status',
    description: '이벤트 드리븐 프로젝션(settlement_*_view) 적재 상태 — 뷰별 row 수·금액 게이지. order 원천과 차이가 나면 프로젝션 lag/누락 의심. (Prometheus settlement.projection.*)',
    inputSchema: { type: 'object', properties: {} },
    run: () => getPrometheus(SETTLEMENT, ['settlement_projection_rows', 'settlement_projection_amount']),
  },
  {
    name: 'outbox_status',
    description: 'Outbox 발행 적체 상태 — pending/failed 건수. pending 이 계속 증가하면 폴러 장애, failed>0 이면 DLQ 후보. (Prometheus outbox.*)',
    inputSchema: {
      type: 'object',
      properties: { service: { type: 'string', enum: ['order', 'settlement'], description: '조회 대상 서비스 (default: order)' } },
    },
    run: ({ service }) => getPrometheus(service === 'settlement' ? SETTLEMENT : ORDER,
      ['outbox_pending_count', 'outbox_failed_count', 'outbox_dlq_published']),
  },
  {
    name: 'pg_recon_runs',
    description: 'PG 정산파일 대사 실행 이력 — 상태(RUNNING/COMPLETED/FAILED), 매칭/불일치/자동보정 건수. (settlement /admin/pg-reconciliation/runs)',
    inputSchema: {
      type: 'object',
      properties: { limit: { type: 'integer', description: 'default 20' } },
    },
    run: ({ limit }) => getJson(SETTLEMENT, `/admin/pg-reconciliation/runs?limit=${Number(limit ?? 20)}`),
  },
  {
    name: 'settlement_simulate',
    description: '정산 dry-run 계산기 — 주문금액·셀러등급으로 수수료/홀드백/즉시지급액/정산주기를 도메인 정책(SellerTier, HoldbackPolicy)대로 계산. 네트워크 불필요, 코드 리뷰 시 기대값 검증용.',
    inputSchema: {
      type: 'object',
      properties: {
        amount: { type: 'string', description: 'KRW 정수 금액(원), e.g. "1000000"' },
        tier: { type: 'string', enum: ['NORMAL', 'VIP', 'STRATEGIC'] },
      },
      required: ['amount', 'tier'],
    },
    run: (args) => simulate(args),
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
          serverInfo: { name: 'settlement-copilot', version: '0.1.0' },
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
