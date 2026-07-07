#!/usr/bin/env node
/**
 * invest-copilot MCP server (stdio, zero-dependency).
 *
 * financial(8086)/economics(8087)/company(8090) 3개 서비스의 공개 GET API 만 프록시한다.
 * - 쓰기 API 는 어떤 것도 라우팅하지 않는다 (read-only by construction).
 * - 주가/시가총액 데이터는 플랫폼에 없다 — PER/PBR/목표주가 도구는 존재하지 않는다.
 * - 비율(%)은 분석용 지표라 JS number 로 계산하되 소수 2자리 고정. 원 단위 금액은
 *   재계산하지 않고 서버 응답 그대로 전달한다.
 *
 * env:
 *   FINANCIAL_BASE_URL (default http://localhost:8086)
 *   ECONOMICS_BASE_URL (default http://localhost:8087)
 *   COMPANY_BASE_URL   (default http://localhost:8090)
 */

import { createInterface } from 'node:readline';
import { checkFileContent, checkCommand } from '../../hooks/guards/rules.mjs';

const FIN = (process.env.FINANCIAL_BASE_URL ?? 'http://localhost:8086').replace(/\/$/, '');
const ECO = (process.env.ECONOMICS_BASE_URL ?? 'http://localhost:8087').replace(/\/$/, '');
const COM = (process.env.COMPANY_BASE_URL ?? 'http://localhost:8090').replace(/\/$/, '');

const DISCLAIMER =
  '본 정보는 교육 목적의 정보 제공이며 투자자문·투자권유가 아닙니다. 투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.';

async function getJson(base, path) {
  const res = await fetch(base + path, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(10_000),
  });
  if (res.status === 204) return null; // company reputation 미산정
  const text = await res.text();
  if (!res.ok) throw new Error(`${base}${path} → HTTP ${res.status}: ${text.slice(0, 300)}`);
  try { return JSON.parse(text); } catch { return text; }
}

const isoDaysAgo = days => new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);
const round2 = n => Math.round(n * 100) / 100;

// ── 재무 지표 (연도별, CFS 연결 우선) ────────────────────────────────────────
function pickPerYear(statements) {
  const byYear = new Map();
  for (const s of statements) {
    const cur = byYear.get(s.fiscalYear);
    if (!cur || (cur.fsDivision !== 'CFS' && s.fsDivision === 'CFS')) byYear.set(s.fiscalYear, s);
  }
  return [...byYear.values()].sort((a, b) => b.fiscalYear - a.fiscalYear);
}

function computeMetrics(rows) {
  return rows.map((s, i) => {
    const prev = rows[i + 1]; // 다음 원소 = 전년
    const roe = s.netIncome != null && s.totalEquity > 0
      ? round2((s.netIncome / s.totalEquity) * 100) : null;
    const revenueGrowth = prev && prev.revenue > 0 && s.revenue != null
      ? round2(((s.revenue - prev.revenue) / prev.revenue) * 100) : null;
    return {
      fiscalYear: s.fiscalYear,
      fsDivision: s.fsDivision,
      source: s.source,
      revenue: s.revenue, operatingProfit: s.operatingProfit, netIncome: s.netIncome,
      totalAssets: s.totalAssets, totalLiabilities: s.totalLiabilities, totalEquity: s.totalEquity,
      revenueGrowthPct: revenueGrowth,
      operatingMarginPct: s.operatingMargin,
      netMarginPct: s.netMargin,
      debtRatioPct: s.debtRatio,        // null = 자본잠식 (서버 규칙)
      equityRatioPct: s.equityRatio,
      roaPct: s.roa,
      roePct: roe,                      // 서버 미제공 — 순이익/자본총계 로컬 계산
      capitalImpaired: s.totalEquity != null && s.totalEquity < 0,
    };
  });
}

// ── 거시 사이클 판정 (invest_signal 내부용) ─────────────────────────────────
function trendOf(points) {
  // 최근 6개월 시계열에서 첫/중간/끝 값으로 방향 판정
  if (!points || points.length < 2) return { direction: 'unknown', first: null, last: null };
  const first = points[0], last = points[points.length - 1];
  const diff = last.value - first.value;
  const eps = Math.abs(first.value) * 0.001 + 1e-9;
  return {
    direction: diff > eps ? 'rising' : diff < -eps ? 'falling' : 'flat',
    first: { date: first.observedDate, value: first.value },
    last: { date: last.observedDate, value: last.value },
    changePct: first.value !== 0 ? round2((diff / first.value) * 100) : null,
  };
}

// ── Tool registry ────────────────────────────────────────────────────────────
const TOOLS = [
  {
    name: 'company_search',
    description: '기업 검색 — 기업명 일부 또는 종목코드로 코스피 상장사 조회 (stockCode/corpCode/name/market). 모든 종목 도구의 진입점: 여기서 stockCode(6자리)를 확정한다. (financial /api/financial/companies)',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string', description: '기업명 일부 또는 종목코드' },
        size: { type: 'integer', description: '결과 수 (기본 20)' },
      },
      required: ['keyword'],
    },
    run: ({ keyword, size }) =>
      getJson(FIN, `/api/financial/companies?keyword=${encodeURIComponent(keyword)}&size=${Number(size ?? 20)}`),
  },
  {
    name: 'fin_statements',
    description: '연간 요약 재무제표 원본 — 매출/영업이익/순이익/자산/부채/자본 + 서버 계산 비율(영업이익률·순이익률·부채비율·자기자본비율·ROA). fsDivision: CFS(연결)/OFS(별도). 금액은 원 단위. 분기 데이터·주가 없음. (financial /api/financial/companies/{stockCode}/statements)',
    inputSchema: {
      type: 'object',
      properties: {
        stockCode: { type: 'string', description: '6자리 종목코드' },
        fromYear: { type: 'integer' }, toYear: { type: 'integer' },
      },
      required: ['stockCode'],
    },
    run: ({ stockCode, fromYear, toYear }) => {
      const q = [fromYear && `fromYear=${Number(fromYear)}`, toYear && `toYear=${Number(toYear)}`]
        .filter(Boolean).join('&');
      return getJson(FIN, `/api/financial/companies/${encodeURIComponent(stockCode)}/statements${q ? `?${q}` : ''}`);
    },
  },
  {
    name: 'fin_metrics',
    description: 'BUY-8 정량 판정용 연도별 지표 — 연결(CFS) 우선으로 연도당 1행을 골라 매출 YoY 성장률(revenueGrowthPct)·ROE(roePct, 순이익/자본총계 로컬 계산)를 보태 반환. debtRatioPct=null 은 자본잠식. 비율 직접 재계산 금지 — 이 값을 인용하라.',
    inputSchema: {
      type: 'object',
      properties: { stockCode: { type: 'string', description: '6자리 종목코드' } },
      required: ['stockCode'],
    },
    run: async ({ stockCode }) => {
      const raw = await getJson(FIN, `/api/financial/companies/${encodeURIComponent(stockCode)}/statements`);
      if (!Array.isArray(raw) || raw.length === 0) throw new Error(`재무제표 없음: ${stockCode}`);
      const rows = pickPerYear(raw);
      return { stockCode, years: computeMetrics(rows).slice(0, 4), note: '연도당 CFS(연결) 우선 선택. roePct 는 로컬 계산(소수 2자리).' };
    },
  },
  {
    name: 'econ_latest',
    description: '4대 경제지표 최신값 — BASE_RATE(기준금리 %), TREASURY_3Y(국고채3년 %), USD_KRW(원/달러), CPI(2020=100, 월별). latest{observedDate,value} 와 전기 대비 change{amount,ratePercent} 포함. 기준일(observedDate)이 오래됐으면 그 사실을 명시할 것. (economics /api/economics/indicators)',
    inputSchema: { type: 'object', properties: {} },
    run: () => getJson(ECO, '/api/economics/indicators'),
  },
  {
    name: 'econ_series',
    description: '경제지표 시계열 — 사이클/추세 판정용. code: BASE_RATE|TREASURY_3Y|USD_KRW|CPI. months 로 조회 구간 지정(기본 6개월). (economics /api/economics/indicators/{code}/series)',
    inputSchema: {
      type: 'object',
      properties: {
        code: { type: 'string', enum: ['BASE_RATE', 'TREASURY_3Y', 'USD_KRW', 'CPI'] },
        months: { type: 'integer', description: '조회 개월 수 (기본 6, 최대 36)' },
      },
      required: ['code'],
    },
    run: ({ code, months }) => {
      const m = Math.max(1, Math.min(Number(months ?? 6), 36));
      return getJson(ECO, `/api/economics/indicators/${encodeURIComponent(code)}/series?from=${isoDaysAgo(m * 30)}&to=${isoDaysAgo(0)}`);
    },
  },
  {
    name: 'news_recent',
    description: '기업 뉴스 기사 목록 — 제목/요약/발행처/링크/발행시각 (본문 미저장, 인용 시 링크 병기). source 로 NAVER_NEWS|DART_DISCLOSURE|RSS 필터 가능. (company /api/company/companies/{stockCode}/articles)',
    inputSchema: {
      type: 'object',
      properties: {
        stockCode: { type: 'string', description: '6자리 종목코드' },
        size: { type: 'integer', description: '기사 수 (기본 20)' },
        source: { type: 'string', enum: ['NAVER_NEWS', 'DART_DISCLOSURE', 'RSS'] },
      },
      required: ['stockCode'],
    },
    run: ({ stockCode, size, source }) =>
      getJson(COM, `/api/company/companies/${encodeURIComponent(stockCode)}/articles?size=${Number(size ?? 20)}${source ? `&source=${source}` : ''}`),
  },
  {
    name: 'reputation_score',
    description: '기업 평판 — 최신 스냅샷(score 0~100, grade A~E, 긍/부정/중립 기사 수, negativeByCategory: FINANCIAL/LEGAL/GOVERNANCE/LABOR/PRODUCT) + 최근 추이. latest=null 이면 아직 미산정(204). LEGAL/FINANCIAL 부정 이슈는 하드 필터급으로 취급 (sentiment-signals skill). (company /api/company/companies/{stockCode}/reputation)',
    inputSchema: {
      type: 'object',
      properties: {
        stockCode: { type: 'string', description: '6자리 종목코드' },
        historyLimit: { type: 'integer', description: '추이 스냅샷 수 (기본 12)' },
      },
      required: ['stockCode'],
    },
    run: async ({ stockCode, historyLimit }) => {
      const sc = encodeURIComponent(stockCode);
      const [latest, history] = await Promise.all([
        getJson(COM, `/api/company/companies/${sc}/reputation`),
        getJson(COM, `/api/company/companies/${sc}/reputation/history?limit=${Number(historyLimit ?? 12)}`),
      ]);
      return { stockCode, latest, history, note: latest === null ? '평판 미산정 (204) — B8 은 unknown 처리' : undefined };
    },
  },
  {
    name: 'invest_signal',
    description: '초보자 매수 체크리스트 BUY-8 종합 자동 판정 — 재무(B1~B5)+거시(B6~B7)+평판(B8)을 한 번에 순회해 항목별 pass/fail/unknown, 하드 필터(적자·부채비율 200%·자본잠식·LEGAL/FINANCIAL 악재), 종합 판정(적극 검토/조건부/보류)을 반환. 개별 도구 결과와 교차 검증용 — 이 판정을 그대로 "매수 권유"로 표현하지 말 것.',
    inputSchema: {
      type: 'object',
      properties: { stockCode: { type: 'string', description: '6자리 종목코드' } },
      required: ['stockCode'],
    },
    run: async ({ stockCode }) => {
      const sc = encodeURIComponent(stockCode);
      const checks = [];
      const add = (id, name, pass, value, basis) => checks.push({ id, name, result: pass, value, basis });

      // 재무 B1~B5
      let m = null;
      try {
        const raw = await getJson(FIN, `/api/financial/companies/${sc}/statements`);
        m = computeMetrics(pickPerYear(Array.isArray(raw) ? raw : []))[0] ?? null;
      } catch (e) { checks.push({ id: 'B1-B5', result: 'unknown', basis: `재무 조회 실패: ${e.message}` }); }
      if (m) {
        const y = `${m.fiscalYear} ${m.fsDivision}`;
        add('B1', '매출 성장 (YoY > 0)', m.revenueGrowthPct == null ? 'unknown' : m.revenueGrowthPct > 0 ? 'pass' : 'fail', m.revenueGrowthPct, y);
        add('B2', '영업이익률 ≥ 5%', m.operatingMarginPct == null ? 'unknown' : m.operatingMarginPct >= 5 ? 'pass' : 'fail', m.operatingMarginPct, y);
        add('B3', '당기순이익 흑자', m.netIncome == null ? 'unknown' : m.netIncome > 0 ? 'pass' : 'fail', m.netIncome, y);
        add('B4', '부채비율 ≤ 150%', m.capitalImpaired || m.debtRatioPct == null ? 'fail' : m.debtRatioPct <= 150 ? 'pass' : 'fail',
          m.debtRatioPct, m.capitalImpaired ? `${y} — 자본잠식` : y);
        add('B5', 'ROE ≥ 8%', m.roePct == null ? 'unknown' : m.roePct >= 8 ? 'pass' : 'fail', m.roePct, `${y} (로컬 계산)`);
      }

      // 거시 B6~B7
      try {
        const base = trendOf((await getJson(ECO, `/api/economics/indicators/BASE_RATE/series?from=${isoDaysAgo(180)}&to=${isoDaysAgo(0)}`))?.points);
        add('B6', '기준금리 인하/동결 사이클', base.direction === 'unknown' ? 'unknown' : base.direction !== 'rising' ? 'pass' : 'fail',
          base.last?.value, `6개월 추이 ${base.direction} (${base.first?.value} → ${base.last?.value})`);
      } catch (e) { add('B6', '기준금리 사이클', 'unknown', null, `조회 실패: ${e.message}`); }
      try {
        const fx = trendOf((await getJson(ECO, `/api/economics/indicators/USD_KRW/series?from=${isoDaysAgo(90)}&to=${isoDaysAgo(0)}`))?.points);
        add('B7', 'USD/KRW 급등 구간 아님 (3개월 +5% 미만)', fx.changePct == null ? 'unknown' : fx.changePct < 5 ? 'pass' : 'fail',
          fx.changePct, `3개월 변화율 % (${fx.first?.value} → ${fx.last?.value}) — 수출주는 실적엔 우호(이중성)`);
      } catch (e) { add('B7', '환율 안정', 'unknown', null, `조회 실패: ${e.message}`); }

      // 평판 B8
      let rep;
      let repFetchFailed = false;
      try { rep = await getJson(COM, `/api/company/companies/${sc}/reputation`); } catch (e) {
        repFetchFailed = true;
        add('B8', '평판 필터', 'unknown', null, `조회 실패: ${e.message}`);
      }
      let legalOrFinancialIssue = false;
      if (!repFetchFailed && rep !== null && rep !== undefined) {
        const cat = rep.negativeByCategory ?? {};
        legalOrFinancialIssue = (cat.LEGAL ?? 0) > 0 || (cat.FINANCIAL ?? 0) > 0;
        const gradeOk = ['A', 'B', 'C'].includes(rep.grade);
        add('B8', '평판 중립 이상 + LEGAL/FINANCIAL 악재 없음',
          gradeOk && !legalOrFinancialIssue ? 'pass' : 'fail',
          `score ${rep.score} / grade ${rep.grade}`,
          `${rep.snapshotDate} 스냅샷, 부정 카테고리: ${JSON.stringify(cat)}`);
      } else if (!repFetchFailed && rep === null) {
        add('B8', '평판 필터', 'unknown', null, '평판 미산정(204) — 표본 부족일 수 있음');
      }

      const hardFilters = [];
      if (m) {
        if (m.netIncome != null && m.netIncome <= 0) hardFilters.push('당기순이익 적자 (B3)');
        if (m.capitalImpaired) hardFilters.push('자본잠식');
        else if (m.debtRatioPct != null && m.debtRatioPct > 200) hardFilters.push(`부채비율 ${m.debtRatioPct}% > 200%`);
      }
      if (legalOrFinancialIssue) hardFilters.push('LEGAL/FINANCIAL 부정 이슈 존재');

      const passed = checks.filter(c => c.result === 'pass').length;
      const unknowns = checks.filter(c => c.result === 'unknown').map(c => c.id);
      const verdict = hardFilters.length > 0 ? '보류 (하드 필터)'
        : passed >= 7 ? '적극 검토' : passed >= 5 ? '조건부 검토 (분할 매수 1차 30% 이내)' : '보류';

      return {
        stockCode, checklist: checks, passed, total: 8, unknowns, hardFilters, verdict,
        caveat: '금융업은 부채비율 기준 부적용 등 업종 보정 필요 (buy-sell-criteria skill). unknown 항목은 충족으로 세지 않음.',
        disclaimer: DISCLAIMER,
      };
    },
  },
  {
    name: 'guard_check',
    description: '표현 컴플라이언스 사전 검사 (로컬, 네트워크 불필요) — 투자 관련 문서(.md/.txt/.html)를 쓰기 전 file_path+content 로, DB 접속 명령 실행 전 command 로 호출. blocked=true 면 보장/단정 표현이 있다는 뜻 — compliance-language skill 의 변환 표를 따라 고쳐 쓸 것.',
    inputSchema: {
      type: 'object',
      properties: {
        file_path: { type: 'string' }, content: { type: 'string' }, command: { type: 'string' },
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
          serverInfo: { name: 'invest-copilot', version: '0.1.0' },
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
