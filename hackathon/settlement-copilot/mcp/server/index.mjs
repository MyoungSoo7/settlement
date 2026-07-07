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
import { checkFileContent, checkCommand } from '../../hooks/guards/rules.mjs';

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
    description: '정산 일일 대사 실행/조회 — 해당 날짜의 결제/환불/정산 금액·건수(INV-9) 일치 검증. matched=false 면 금액이 새는 중. window=N 이면 date 부터 과거 N일 재대사(지연 환불 소급 탐지, INV-8 보조). (settlement /admin/reconciliation)',
    inputSchema: {
      type: 'object',
      properties: {
        date: { type: 'string', description: 'ISO date, e.g. 2026-07-06' },
        window: { type: 'integer', description: '재대사 일수 — date 포함 과거 N일 (기본 1, 최대 31)' },
      },
      required: ['date'],
    },
    run: async ({ date, window }) => {
      const days = Math.max(1, Math.min(Number(window ?? 1), 31));
      if (days === 1) return getJson(SETTLEMENT, `/admin/reconciliation?date=${encodeURIComponent(date)}`);
      const base = new Date(`${date}T00:00:00Z`);
      const reports = [];
      for (let i = 0; i < days; i++) {
        const d = new Date(base);
        d.setUTCDate(d.getUTCDate() - i);
        const iso = d.toISOString().slice(0, 10);
        try {
          reports.push(await getJson(SETTLEMENT, `/admin/reconciliation?date=${iso}`));
        } catch (e) {
          reports.push({ targetDate: iso, error: String(e?.message ?? e) });
        }
      }
      return {
        window: days,
        allMatched: reports.every(r => r.matched === true),
        mismatchedDates: reports.filter(r => r.matched !== true).map(r => r.targetDate),
        reports,
      };
    },
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
  // ── Integrity Suite Phase A (INV-5/6/7/11 — settlement /admin/integrity/*) ──
  {
    name: 'integrity_check',
    description: '정합성 종합 점검 — 원장 완전성(INV-5)·지급 대사(INV-6)·홀드백(INV-7)·상태 체류(INV-11)를 한 번에 순회. allOk=false 면 개별 도구(ledger_completeness 등)로 드릴다운. "돈이 새는가"의 대표 진입점.',
    inputSchema: {
      type: 'object',
      properties: { date: { type: 'string', description: 'ISO date — 대사 기준일 (ledger/payout 축)' } },
      required: ['date'],
    },
    run: async ({ date }) => {
      const d = encodeURIComponent(date);
      const targets = [
        ['ledger_completeness(INV-5)', `/admin/integrity/ledger-completeness?date=${d}`],
        ['payout_recon(INV-6)', `/admin/integrity/payout-recon?date=${d}`],
        ['holdback_status(INV-7)', '/admin/integrity/holdback-status'],
        ['stuck_states(INV-11)', '/admin/integrity/stuck'],
        ['refund_adjustments(INV-8)', `/admin/integrity/refund-adjustments?from=${d}&to=${d}`],
      ];
      const settled = await Promise.allSettled(targets.map(([, p]) => getJson(SETTLEMENT, p)));
      const checks = settled.map((r, i) => r.status === 'fulfilled'
        ? { name: targets[i][0], ok: r.value?.ok === true, report: r.value }
        : { name: targets[i][0], ok: false, error: String(r.reason?.message ?? r.reason) });
      return {
        date,
        allOk: checks.every(c => c.ok),
        checkedInvariants: ['INV-5', 'INV-6', 'INV-7', 'INV-8', 'INV-11'],
        checks,
      };
    },
  },
  {
    name: 'ledger_completeness',
    description: 'INV-5 원장 완전성 — 확정(DONE) 정산·환불 조정마다 분개가 실재하고 금액이 맞는지 대조. 시산표(차/대 균형)가 못 잡는 "통짜 누락"을 감지. grace 이내 미처리는 pendingWithinGrace 로 구분(정상). (settlement /admin/integrity/ledger-completeness)',
    inputSchema: {
      type: 'object',
      properties: {
        date: { type: 'string', description: 'ISO date — 확정일 기준' },
        graceMinutes: { type: 'integer', description: '비동기 grace window 재정의 (기본 15분)' },
      },
      required: ['date'],
    },
    run: ({ date, graceMinutes }) => getJson(SETTLEMENT,
      `/admin/integrity/ledger-completeness?date=${encodeURIComponent(date)}`
      + (graceMinutes ? `&graceMinutes=${Number(graceMinutes)}` : '')),
  },
  {
    name: 'payout_recon',
    description: 'INV-6 지급 대사 — 그날 확정 정산 ↔ payout 금액·중복 대조. 과다 지급(payout > net)·이중 payout 은 위반, payout 미생성(settlementsWithoutPayout)은 정보성. (settlement /admin/integrity/payout-recon)',
    inputSchema: {
      type: 'object',
      properties: { date: { type: 'string', description: 'ISO date — 확정일 기준' } },
      required: ['date'],
    },
    run: ({ date }) => getJson(SETTLEMENT, `/admin/integrity/payout-recon?date=${encodeURIComponent(date)}`),
  },
  {
    name: 'holdback_status',
    description: 'INV-7 홀드백 — 해제일이 지났는데 미해제인 보류금 감지 (HoldbackReleaseScheduler 침묵 정지 감지). lastReleasedAt 이 오래됐으면 배치 생존 의심. (settlement /admin/integrity/holdback-status)',
    inputSchema: { type: 'object', properties: {} },
    run: () => getJson(SETTLEMENT, '/admin/integrity/holdback-status'),
  },
  {
    name: 'stuck_states',
    description: 'INV-11 상태 체류 — settlement PROCESSING·확정 지연 / payout SENDING(이중지급 위험 1순위) / PG 대사 RUNNING / ledger_outbox PENDING·FAILED 장기 체류 감지. (settlement /admin/integrity/stuck)',
    inputSchema: {
      type: 'object',
      properties: { thresholdMinutes: { type: 'integer', description: '체류 임계(분), 기본 60' } },
    },
    run: ({ thresholdMinutes }) => getJson(SETTLEMENT,
      '/admin/integrity/stuck' + (thresholdMinutes ? `?thresholdMinutes=${Number(thresholdMinutes)}` : '')),
  },
  {
    name: 'refund_adjustments',
    description: 'INV-8 지연 환불 조정 대사 — COMPLETED 환불(완료일 기준) ↔ settlement 조정(역정산) 존재 대조. 일일 대사(캡처일 축)의 사각지대인 지연 환불의 조정 누락을 감지. to 는 어제 이전 권장. (settlement /admin/integrity/refund-adjustments)',
    inputSchema: {
      type: 'object',
      properties: {
        from: { type: 'string', description: 'ISO date' },
        to: { type: 'string', description: 'ISO date — 어제 이전 권장 (당일 환불은 컨슈머 처리 중일 수 있음)' },
      },
      required: ['from', 'to'],
    },
    run: ({ from, to }) => getJson(SETTLEMENT,
      `/admin/integrity/refund-adjustments?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  },
  {
    name: 'event_accounting',
    description: 'INV-10 이벤트 회계 — order outbox 의 PaymentCaptured PUBLISHED 건수(발행) vs settlement processed_events 소비 건수를 그룹별로 대조. gap>0 이면 유실/적체 의심 → outbox_status·DLT 로 드릴다운. (order /internal/recon/period-totals + settlement /admin/integrity/processed-count)',
    inputSchema: {
      type: 'object',
      properties: {
        from: { type: 'string', description: 'ISO date' },
        to: { type: 'string', description: 'ISO date' },
      },
      required: ['from', 'to'],
    },
    run: async ({ from, to }) => {
      const f = encodeURIComponent(from), t = encodeURIComponent(to);
      const [order, processed] = await Promise.all([
        getJson(ORDER, `/internal/recon/period-totals?from=${f}&to=${t}`, { internal: true }),
        getJson(SETTLEMENT, `/admin/integrity/processed-count?from=${f}&to=${t}`),
      ]);
      const published = Number(order?.paymentCapturedPublishedCount ?? 0);
      const rows = Array.isArray(processed) ? processed : [];
      const capturedGroups = rows
        .filter(r => String(r.eventType ?? '').includes('PaymentCaptured'))
        .map(r => {
          const cnt = Number(r.count);
          return { consumerGroup: r.consumerGroup, processed: cnt, gap: published - cnt, suspectedLoss: published - cnt > 0 };
        });
      const ok = published === 0 || (capturedGroups.length > 0 && capturedGroups.every(g => g.gap <= 0));
      return {
        from, to,
        checkedInvariants: ['INV-10'],
        publishedPaymentCaptured: published,
        paymentCapturedGroups: capturedGroups,
        processedByGroup: rows,
        ok,
        note: ok
          ? '발행 건수 이상을 소비 확인 (published ≤ processed)'
          : 'published − processed = gap > 0 — 유실/적체 의심. outbox_status → DLT → processed_events 순으로 원인 추적',
      };
    },
  },
  {
    name: 'guard_check',
    description: '가드 규칙 사전 검사 (로컬 실행, 네트워크 불필요) — 실시간 훅이 없는 환경(Codex CLI 등)에서 훅과 동일한 rules.mjs 로 검사한다. 금액 스코프 파일(settlement/ledger/payout/loan/payment/recon 경로의 .java/.kt)을 쓰기 전에는 file_path+content 로, DB 클라이언트·kafka produce 명령을 실행하기 전에는 command 로 호출하라. blocked=true 면 그 내용을 쓰거나 명령을 실행하지 말고 violations 의 지시를 따를 것.',
    inputSchema: {
      type: 'object',
      properties: {
        file_path: { type: 'string', description: '검사할 파일 경로 (content 와 함께 사용)' },
        content: { type: 'string', description: '파일에 쓰려는 전체 내용' },
        command: { type: 'string', description: '실행하려는 셸 명령 (file_path/content 대신 사용)' },
      },
    },
    run: ({ file_path, content, command }) => {
      if (command) {
        const violations = checkCommand(command);
        return { mode: 'command', blocked: violations.some(v => v.severity === 'BLOCK'), violations };
      }
      if (file_path && typeof content === 'string') {
        const violations = checkFileContent(file_path, content);
        return { mode: 'file', file_path, blocked: violations.some(v => v.severity === 'BLOCK'), violations };
      }
      throw new Error('command 또는 (file_path + content) 가 필요합니다');
    },
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
          serverInfo: { name: 'settlement-copilot', version: '0.2.0' },
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
