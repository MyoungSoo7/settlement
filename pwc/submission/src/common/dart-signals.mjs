/**
 * 외부(공시) 신호 파생 엔진 — DART 전체 재무제표 + 공시 목록에서 경영리스크 신호를 파생한다.
 *
 * 기본 모드의 심장: 내부 CSV 없이 **API 로 얻을 수 있는 자료만으로** 진단이 성립하게 한다.
 * fnlttSinglAcntAll(전체 재무제표) 1회 호출이 당기/전기/전전기 3개년의 매출·매출채권·재고·
 * 영업활동현금흐름·차입금·이자지급을 모두 주므로, 내부 signals.mjs 와 같은
 * "임계값 기반 판정 + 계산값 마커" 방식으로 외부 신호 5종을 파생한다.
 *
 * 외부 신호 (E1~E5, E8):
 *   E1 수익-채권 괴리   — 매출채권 증가율이 매출 증가율을 크게 상회 + 영업CF/영업이익 비율 저조
 *   E2 재고 자산 적체   — 재고 증가율이 매출 증가율을 크게 상회
 *   E3 차입 확대·이자 부담 — 차입금(단기+장기+사채) 급증 + 이자보상(영업이익/이자지급) 저위
 *   E4 유동성 하락      — 유동비율 절대 저위 또는 2년 연속 하락 + 주의 수준
 *   E5 공시 행간        — 정정공시 빈발·풍문 해명 등 공시 커뮤니케이션 신호
 *   E8 발생액 품질      — 당기순이익 대비 영업현금흐름 괴리(총자산 대비 발생액 비율) — 회계학
 *                         발생액 기반 이익 품질 축 (E6·E7 은 market-signals.mjs 의 시장 축)
 *
 * 한계(정직 고지): 공시는 연간·법인 단위 요약이다. 거래처별 채권 집중(S2)·제품별 원가
 * 배분 왜곡(S3) 같은 상세 축은 내부 CSV(상세 모드)에서만 판정된다.
 */
import { num } from './csv.mjs';
import { pctMarker, amountMarker } from './signals.mjs';

export const EXTERNAL_THRESHOLDS = {
  arGrowthGapPp: 15,        // E1: (매출채권 증가율 − 매출 증가율) 최소 격차 %p
  ocfToOiFloor: 0.8,        // E1: 영업CF/영업이익 비율 하한 (이 미만이면 이익의 현금화 저조)
  inventoryGapPp: 15,       // E2: (재고 증가율 − 매출 증가율) 최소 격차 %p
  borrowingsGrowthPct: 30,  // E3: 차입금 합계 증가율
  interestCoverageFloor: 10,// E3: 이자보상배율(영업이익/이자지급) 하한
  // E4 유동비율 — 2026-07 코스피 비금융 대형주 15사 캘리브레이션(bin/calibrate.mjs)에서
  // 120/150 기준 발화율 33%로 과민 판정 → 100(유동부채>유동자산)/130 으로 보정.
  currentRatioFloorPct: 100,// E4: 유동비율 절대 하한 %
  currentRatioWatchPct: 130,// E4: 2년 연속 하락 시 주의 수준 %
  correctionsMin: 3,        // E5: 관측 기간 내 정정공시 최소 건수
  clarificationsMin: 1,     // E5: 풍문·보도 해명 최소 건수
  // E8 발생액 품질 — Sloan(1996) 이후 발생액 문헌에서 "총자산 대비 총발생액(NI−OCF)" 상위
  // 십분위가 대략 +10% 부근: 이 수준을 넘는 이익은 현금 뒷받침 없는 발생액 비중이 커서
  // 이후 이익 반전(감액·대손) 확률이 유의하게 높다. 지속 괴리(2년 연속 흑자인데 OCF 가
  // 순이익의 절반 미만)는 일회성 아닌 구조적 신호로 별도 OR 조건.
  accrualRatioCeilingPct: 10, // E8: (당기순이익 − 영업CF) / 총자산 상한 %
  ocfToNiFloor: 0.5,          // E8: 지속 괴리 판정용 OCF/순이익 하한 (2년 연속)
};

// fnlttSinglAcntAll 계정 추출 좌표 — 표준 XBRL account_id 우선, 계정명 별칭 폴백.
const ACCOUNTS = {
  revenue: { ids: ['ifrs-full_Revenue'], names: ['매출액', '영업수익', '수익(매출액)'], sj: ['IS', 'CIS'] },
  operatingIncome: { ids: ['dart_OperatingIncomeLoss'], names: ['영업이익', '영업이익(손실)'], sj: ['IS', 'CIS'] },
  receivables: {
    ids: ['ifrs-full_CurrentTradeReceivables', 'ifrs-full_TradeAndOtherCurrentReceivables'],
    names: ['매출채권', '매출채권및기타채권', '매출채권 및 기타유동채권'], sj: ['BS'],
  },
  inventories: { ids: ['ifrs-full_Inventories'], names: ['재고자산'], sj: ['BS'] },
  currentAssets: { ids: ['ifrs-full_CurrentAssets'], names: ['유동자산'], sj: ['BS'] },
  currentLiabilities: { ids: ['ifrs-full_CurrentLiabilities'], names: ['유동부채'], sj: ['BS'] },
  shortBorrowings: { ids: ['ifrs-full_ShorttermBorrowings'], names: ['단기차입금'], sj: ['BS'] },
  longBorrowings: { ids: ['ifrs-full_NoncurrentPortionOfNoncurrentLoansReceived', 'ifrs-full_LongtermBorrowings'], names: ['장기차입금'], sj: ['BS'] },
  bonds: { ids: ['ifrs-full_NoncurrentPortionOfNoncurrentBondsIssued', 'ifrs-full_BondsIssued'], names: ['사채'], sj: ['BS'] },
  ocf: {
    ids: ['ifrs-full_CashFlowsFromUsedInOperatingActivities'],
    names: ['영업활동현금흐름', '영업활동으로인한현금흐름', '영업활동으로 인한 현금흐름'], sj: ['CF'],
  },
  interestPaid: {
    ids: ['ifrs-full_InterestPaidClassifiedAsOperatingActivities', 'ifrs-full_InterestPaidClassifiedAsFinancingActivities'],
    names: ['이자의 지급', '이자지급', '이자의지급'], sj: ['CF'],
  },
  netIncome: {
    ids: ['ifrs-full_ProfitLoss'],
    names: ['당기순이익', '당기순이익(손실)', '당기순손익'], sj: ['IS', 'CIS'],
  },
  totalAssets: { ids: ['ifrs-full_Assets'], names: ['자산총계'], sj: ['BS'] },
};

const toNum = (v) => {
  if (v === undefined || v === null || String(v).trim() === '' || String(v).trim() === '-') return null;
  try { return num(v, 'dart'); } catch { return null; }
};

/**
 * fnlttSinglAcntAll 응답에서 3개년 시계열을 추출한다.
 * @returns {{ [key]: [전전기, 전기, 당기] | null }} — 계정 미존재 시 null
 */
export function extractFullSeries(body) {
  const list = body.list ?? [];
  const series = {};
  for (const [key, spec] of Object.entries(ACCOUNTS)) {
    const row =
      list.find((r) => spec.sj.includes(r.sj_div) && spec.ids.includes(r.account_id))
      ?? list.find((r) => spec.sj.includes(r.sj_div) && spec.names.includes(String(r.account_nm).trim()));
    series[key] = row
      ? [toNum(row.bfefrmtrm_amount), toNum(row.frmtrm_amount), toNum(row.thstrm_amount)]
      : null;
  }
  return series;
}

const growthPct = (prev, cur) =>
  prev === null || cur === null || prev === 0 ? null : ((cur - prev) / Math.abs(prev)) * 100;
const round1 = (v) => (v === null ? null : Math.round(v * 10) / 10);
const round2 = (v) => (v === null ? null : Math.round(v * 100) / 100);
/** KRW → 조 단위 문자열 (사람용 근거 수치) */
export const toTrillion = (v) => (v === null ? null : Math.round((v / 1e12) * 10) / 10);
/** 조 단위 마커 — 24.06조 → 24[.,]1\s*조 (반올림 1자리) */
export function trillionMarker(v) {
  const t = Math.abs(v) / 1e12;
  const [i, d] = (Math.round(t * 10) / 10).toFixed(1).split('.');
  return d === '0'
    ? new RegExp(`(?<!\\d)${i}([.,]0)?\\s*조`)
    : new RegExp(`(?<!\\d)${i}[.,]${d}\\s*조`);
}

/** 3개년 합산 차입금 [전전기, 전기, 당기] — 구성 항목이 전부 없으면 null */
export function totalBorrowings(series) {
  const parts = ['shortBorrowings', 'longBorrowings', 'bonds'].map((k) => series[k]).filter(Boolean);
  if (parts.length === 0) return null;
  return [0, 1, 2].map((i) => parts.reduce((acc, s) => acc + (s[i] ?? 0), 0));
}

/**
 * 공시 목록(list.json rows)에서 커뮤니케이션 신호를 집계한다.
 */
export function analyzeDisclosures(rows) {
  const titles = (rows ?? []).map((r) => ({ name: String(r.report_nm).trim(), date: r.rcept_dt }));
  const corrections = titles.filter((t) => /정정/.test(t.name));
  const clarifications = titles.filter((t) => /풍문|해명/.test(t.name));
  const audit = titles.filter((t) => /감사보고서|의견거절|한정/.test(t.name));
  return { total: titles.length, corrections, clarifications, audit };
}

/**
 * 외부 신호 5종 파생 — 내부 signals.mjs 와 동일한 신호 객체 형태를 반환한다
 * (briefing-eval --signals-file 로 그대로 채점 가능).
 */
export function deriveExternalSignals({ series, disclosures, days = 90 }, thresholds = EXTERNAL_THRESHOLDS) {
  const t = { ...EXTERNAL_THRESHOLDS, ...thresholds };
  const signals = [];
  const notEvaluable = (id, name, categoryPattern, note) =>
    ({ id, name, present: false, evaluable: false, note, evidence: {}, markers: [], categoryPattern, checkHints: [] });

  // ── E1: 수익-채권 괴리 (전기→당기) ─────────────────────────
  {
    const id = 'E1';
    const name = '수익-채권 괴리 (이익의 현금화 저조)';
    // E1 은 '수익 조기 인식' + '매출채권 급증·괴리' 리스크 — 중립어("수익 인식")나 은행·지주의
    // 대출채권이 E8 등 다른 축 서술에 등장해 E1 로 오탐되던 것을 막는다(E1 marker 도 /매출채권/).
    const categoryPattern = /수익\s*조기\s*인식|조기\s*인식|매출\s*채권.*(급증|괴리)|현금화\s*저조/;
    const rev = series.revenue; const recv = series.receivables;
    const oi = series.operatingIncome; const ocf = series.ocf;
    if (!rev || !recv) {
      signals.push(notEvaluable(id, name, categoryPattern, '공시에서 매출액/매출채권 계정을 찾지 못함'));
    } else {
      const revGrowth = growthPct(rev[1], rev[2]);
      const recvGrowth = growthPct(recv[1], recv[2]);
      const gapPp = revGrowth !== null && recvGrowth !== null ? recvGrowth - revGrowth : null;
      const ocfToOi = oi && ocf && oi[2] ? ocf[2] / oi[2] : null;
      const present = gapPp !== null && gapPp >= t.arGrowthGapPp
        && (ocfToOi === null || ocfToOi < t.ocfToOiFloor);
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          revenueGrowthPct: round1(revGrowth), receivablesGrowthPct: round1(recvGrowth),
          gapPp: round1(gapPp),
          receivablesTrillion: `${toTrillion(recv[1])}→${toTrillion(recv[2])}조`,
          ocfToOperatingIncome: round2(ocfToOi),
        },
        markers: present ? [pctMarker(revGrowth), pctMarker(recvGrowth), /매출채권/, /현금흐름|현금화/] : [],
        categoryPattern,
        checkHints: ['분기보고서로 채권 증가 시점 좁히기', '대손충당금 설정률 추이', '주석의 매출채권 연령 분석'],
      });
    }
  }

  // ── E2: 재고 자산 적체 ─────────────────────────────────────
  {
    const id = 'E2';
    const name = '재고 자산 적체';
    const categoryPattern = /재고\s*(자산)?\s*(적체|급증|과잉)|재고자산.*(증가|부담)/;
    const rev = series.revenue; const inv = series.inventories;
    if (!rev || !inv) {
      signals.push(notEvaluable(id, name, categoryPattern, '공시에서 재고자산 계정을 찾지 못함'));
    } else {
      const revGrowth = growthPct(rev[1], rev[2]);
      const invGrowth = growthPct(inv[1], inv[2]);
      const gapPp = revGrowth !== null && invGrowth !== null ? invGrowth - revGrowth : null;
      const present = gapPp !== null && gapPp >= t.inventoryGapPp;
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          revenueGrowthPct: round1(revGrowth), inventoryGrowthPct: round1(invGrowth), gapPp: round1(gapPp),
          inventoriesTrillion: `${toTrillion(inv[1])}→${toTrillion(inv[2])}조`,
        },
        markers: present ? [pctMarker(invGrowth), pctMarker(revGrowth), /재고/] : [],
        categoryPattern,
        checkHints: ['재고자산 평가손실(주석) 확인', '제품/재공품/원재료 구성 변화', '재고자산회전율 추이'],
      });
    }
  }

  // ── E3: 차입 확대·이자 부담 ────────────────────────────────
  {
    const id = 'E3';
    const name = '차입 확대·이자 부담';
    const categoryPattern = /차입\s*(확대|급증|의존)|이자\s*부담|이자보상/;
    const borrow = totalBorrowings(series);
    const oi = series.operatingIncome; const ip = series.interestPaid;
    if (!borrow) {
      signals.push(notEvaluable(id, name, categoryPattern, '공시에서 차입금 계정을 찾지 못함'));
    } else {
      const borrowGrowth = growthPct(borrow[1], borrow[2]);
      const coverage = oi && ip && ip[2] > 0 ? oi[2] / ip[2] : null;
      const coveragePrev = oi && ip && ip[1] > 0 ? oi[1] / ip[1] : null;
      const coverageDropPct = coverage !== null && coveragePrev ? ((coveragePrev - coverage) / coveragePrev) * 100 : null;
      const present = borrowGrowth !== null && borrowGrowth >= t.borrowingsGrowthPct
        && coverage !== null
        && (coverage < t.interestCoverageFloor || (coverageDropPct !== null && coverageDropPct >= 40));
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          borrowingsTrillion: `${toTrillion(borrow[1])}→${toTrillion(borrow[2])}조`,
          borrowingsGrowthPct: round1(borrowGrowth),
          interestCoverage: coverage === null ? null : `${round1(coveragePrev)}→${round1(coverage)}배 (영업이익/이자지급)`,
          coverageDropPct: round1(coverageDropPct),
        },
        markers: present ? [pctMarker(borrowGrowth), trillionMarker(borrow[2]), /차입/, /이자보상|이자\s*부담/] : [],
        categoryPattern,
        checkHints: ['차입금 만기 구조(주석) 확인', '변동/고정 금리 구성', '약정(covenant) 조건 확인'],
      });
    }
  }

  // ── E4: 유동성 하락 ────────────────────────────────────────
  {
    const id = 'E4';
    const name = '유동성 하락 (유동비율)';
    const categoryPattern = /유동성\s*(하락|압박|악화)|유동비율.*(하락|저하)/;
    const ca = series.currentAssets; const cl = series.currentLiabilities;
    if (!ca || !cl) {
      signals.push(notEvaluable(id, name, categoryPattern, '공시에서 유동자산/유동부채 계정을 찾지 못함'));
    } else {
      const ratios = [0, 1, 2].map((i) => (ca[i] !== null && cl[i] ? (ca[i] / cl[i]) * 100 : null));
      const latest = ratios[2];
      const fallingTwice = ratios.every((r) => r !== null) && ratios[2] < ratios[1] && ratios[1] < ratios[0];
      const present = latest !== null
        && (latest < t.currentRatioFloorPct || (fallingTwice && latest < t.currentRatioWatchPct));
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          currentRatioPct: ratios.map((r) => round1(r)).join('→'),
          fallingTwoYears: fallingTwice,
        },
        markers: present ? [pctMarker(latest), /유동비율|유동성/] : [],
        categoryPattern,
        checkHints: ['단기차입 상환 스케줄', '미사용 여신한도(주석)', '현금성자산 대비 단기부채'],
      });
    }
  }

  // ── E5: 공시 행간 (커뮤니케이션 신호) ──────────────────────
  {
    const id = 'E5';
    const name = '공시 행간 (정정·해명 패턴)';
    const categoryPattern = /정정\s*공시|풍문|해명\s*공시|공시\s*(품질|프로세스|커뮤니케이션)/;
    if (!disclosures) {
      signals.push(notEvaluable(id, name, categoryPattern, '공시 목록 미조회'));
    } else {
      const d = analyzeDisclosures(disclosures);
      const present = d.corrections.length >= t.correctionsMin
        || d.clarifications.length >= t.clarificationsMin
        || d.audit.length > 0;
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          windowDays: days,
          totalDisclosures: d.total,
          corrections: d.corrections.length,
          clarifications: d.clarifications.map((c) => `${c.name} @${c.date}`),
          auditFlags: d.audit.map((c) => `${c.name} @${c.date}`),
        },
        markers: present ? [/정정/, /풍문|해명/] : [],
        categoryPattern,
        checkHints: ['정정 사유 분류(단순 오기/수치 변경)', '해명 공시의 대상 보도와 후속 확정 공시', '공시 검수 체인 점검'],
      });
    }
  }

  // ── E8: 발생액 품질 (이익-현금 괴리) ───────────────────────
  {
    const id = 'E8';
    const name = '발생액 품질 (이익-현금 괴리)';
    const categoryPattern = /발생액|이익\s*(의)?\s*품질|이익.*현금.*(괴리|뒷받침)|현금\s*뒷받침\s*없는\s*이익/;
    const ni = series.netIncome; const ocf = series.ocf; const ta = series.totalAssets;
    if (!ni || !ocf || !ta) {
      signals.push(notEvaluable(id, name, categoryPattern, '공시에서 당기순이익/영업현금흐름/자산총계 계정을 찾지 못함'));
    } else {
      // 총발생액 = 당기순이익 − 영업활동현금흐름 (Sloan 1996 총발생액 근사), 총자산으로 스케일링.
      const accrualRatio = ta[2] ? ((ni[2] - ocf[2]) / ta[2]) * 100 : null;
      const accrualRatioPrev = ta[1] ? ((ni[1] - ocf[1]) / ta[1]) * 100 : null;
      const ocfToNi = ni[2] > 0 && ocf[2] !== null ? ocf[2] / ni[2] : null;
      const ocfToNiPrev = ni[1] > 0 && ocf[1] !== null ? ocf[1] / ni[1] : null;
      const persistentGap = ocfToNi !== null && ocfToNiPrev !== null
        && ocfToNi < t.ocfToNiFloor && ocfToNiPrev < t.ocfToNiFloor;
      const present = (accrualRatio !== null && accrualRatio >= t.accrualRatioCeilingPct) || persistentGap;
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          netIncomeTrillion: `${toTrillion(ni[1])}→${toTrillion(ni[2])}조`,
          ocfTrillion: `${toTrillion(ocf[1])}→${toTrillion(ocf[2])}조`,
          accrualRatioPct: round1(accrualRatio),
          accrualRatioPrevPct: round1(accrualRatioPrev),
          ocfToNetIncome: round2(ocfToNi),
          persistentGapTwoYears: persistentGap,
        },
        markers: present
          ? [...(accrualRatio !== null ? [pctMarker(accrualRatio)] : []), /발생액/, /순이익/, /현금흐름|현금\s*뒷받침/]
          : [],
        categoryPattern,
        checkHints: [
          '발생액 구성 분해 — 운전자본성(매출채권·재고·매입채무) vs 비운전자본성(충당금·감가상각 정책 변경)',
          '수익 인식 정책 변경·추정 변경 주석 확인 (회계 정책 변경이 발생액 급증과 겹치는지)',
          '차기 분기 OCF 실측으로 반증 — 발생액이 정상 회수되면 일시 괴리, 누적되면 이익 품질 리스크',
        ],
      });
    }
  }

  return signals;
}
