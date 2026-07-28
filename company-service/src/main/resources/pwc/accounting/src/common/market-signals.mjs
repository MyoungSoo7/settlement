/**
 * 시장(주가·시총) 신호 파생 엔진 — E6·E7.
 *
 * 주가를 밸류에이션(투자 판단)이 아니라 **대사(reconciliation)** 로 쓴다:
 * 시장이 매기는 값(시총)과 장부의 현금 창출력(영업현금흐름)은 같은 회사를 보는
 * 두 개의 장부이고, 둘의 괴리·공시와의 부정합이 CEO 경영 리스크 신호다.
 * 목표주가·매수/매도 판단은 산출하지 않는다 (플러그인 경계).
 *
 *   E6 시장·현금흐름 괴리 — 시총 급변과 OCF 추세가 반대 방향일 때
 *   E7 공시·주가 정합   — 해명·풍문 공시 직전 주가/거래량 이상 변동 (정보 관리 리스크)
 *
 * 정답지 하드코딩 금지 원칙 그대로: 마커는 계산값에서 생성한다.
 */
import { pctMarker } from './signals.mjs';
import { toTrillion } from './dart-signals.mjs';

// 기본 임계값 — 근거는 각 항목 주석 (변경 시 bin/calibrate.mjs 로 발화율 재측정).
export const MARKET_THRESHOLDS = {
  mcapDropPct: 25,       // E6: 90일 시총 -25% — 코스피 대형주 연 변동성(~30%) 기준, 90일 구간에서 "시장의 뚜렷한 재평가"로 볼 수 있는 하락 폭
  ocfStablePct: 10,      // E6: OCF 감소 10% 이내면 "장부 안정" — 연 단위 회계 변동 노이즈 허용 폭
  ocfDropPct: 30,        // E6: OCF -30% — E1(현금화 저조)과 정합하는 뚜렷한 장부 악화
  mcapStablePct: 10,     // E6: 시총 하락 10% 이내면 "시장 유지" — 조달 창구가 아직 열려 있다고 보는 폭
  preMovePct: 8,         // E7: 해명 공시 직전 5거래일 누적 ±8% — 대형주 일변동성(~1.5%) 기준 5일 정상 범위(±2σ)를 넘는 선행 변동
  volumeSpikeRatio: 2,   // E7: 직전 5거래일 평균 거래량이 기준 20거래일 평균의 2배 이상
  preWindowDays: 5,      // E7: 공시 직전 관찰 거래일 수
  baseWindowDays: 20,    // E7: 거래량 기준선 거래일 수
};

const round1 = (v) => (v === null || v === undefined ? null : Math.round(v * 10) / 10);
const growthPct = (prev, cur) =>
  (prev === null || cur === null || prev === undefined || cur === undefined || prev === 0
    ? null : ((cur - prev) / Math.abs(prev)) * 100);

const notEvaluable = (id, name, categoryPattern, note) =>
  ({ id, name, present: false, evaluable: false, note, evidence: {}, markers: [], categoryPattern, checkHints: [] });

/** 해명·풍문 공시만 추출 (analyzeDisclosures 의 clarification 판정과 동일 기준) */
export function clarificationEvents(disclosures) {
  return (disclosures ?? [])
    .filter((d) => /해명|풍문/.test(String(d?.report_nm ?? '')))
    .map((d) => ({ reportNm: String(d.report_nm).trim(), date: String(d.rcept_dt ?? '').trim() }))
    .filter((d) => /^\d{8}$/.test(d.date));
}

/**
 * prices: krx/client.fetchDailyPrices 결과 (basDt 오름차순).
 * series: dart-signals.extractFullSeries 결과 (ocf 3개년).
 * disclosures: DART 공시 목록 rows.
 */
export function deriveMarketSignals({ prices, series, disclosures, days = 90 }, thresholds = MARKET_THRESHOLDS) {
  const t = { ...MARKET_THRESHOLDS, ...thresholds };
  const signals = [];
  const valid = (prices ?? []).filter((p) => p.close !== null);

  // ── E6: 시장·현금흐름 괴리 ─────────────────────────────────
  {
    const id = 'E6';
    const name = '시장·현금흐름 괴리 (시총 vs 영업현금흐름)';
    // 주의: "주가 하락" 류 일반 표현은 넣지 않는다 — E7(해명 공시 전 주가 변동) 절과
    // 오매칭돼 미발화 E6 오탐을 만든다 (삼성전자 라이브 회귀). E6 의 주제는 시총↔현금흐름 괴리.
    const categoryPattern = /시가총액|시총|시장\s*(평가|가치).{0,12}(괴리|불일치)/;
    const withMcap = valid.filter((p) => p.marketCap !== null);
    const ocf = series?.ocf ?? null;
    if (withMcap.length < 5) {
      signals.push(notEvaluable(id, name, categoryPattern, '시세(시가총액) 데이터 부족 — KRX 조회 실패 또는 비상장'));
    } else if (!ocf) {
      signals.push(notEvaluable(id, name, categoryPattern, '공시에서 영업활동현금흐름 계정을 찾지 못함'));
    } else {
      const first = withMcap[0];
      const last = withMcap[withMcap.length - 1];
      const mcapChangePct = growthPct(first.marketCap, last.marketCap);
      const ocfGrowthPct = growthPct(ocf[1], ocf[2]);
      // 방향 판정: 시장 급락 vs 장부 안정 / 장부 악화 vs 시장 유지
      const marketDrop = mcapChangePct !== null && mcapChangePct <= -t.mcapDropPct
        && (ocfGrowthPct === null || ocfGrowthPct >= -t.ocfStablePct);
      const bookDrop = ocfGrowthPct !== null && ocfGrowthPct <= -t.ocfDropPct
        && mcapChangePct !== null && mcapChangePct > -t.mcapStablePct;
      const present = marketDrop || bookDrop;
      const mode = marketDrop ? 'market-drop' : bookDrop ? 'book-drop' : null;
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          windowDays: days,
          marketCapTrillion: `${toTrillion(first.marketCap)}→${toTrillion(last.marketCap)}조`,
          marketCapChangePct: round1(mcapChangePct),
          ocfGrowthPct: round1(ocfGrowthPct),
          mode,
        },
        markers: present
          ? [pctMarker(mcapChangePct), /시가총액|시총/, /영업(활동)?\s*현금흐름|현금\s*창출/]
          : [],
        categoryPattern,
        checkHints: marketDrop
          ? ['시총 급락 구간의 뉴스·공시 이벤트 대조 (시장이 아는 것을 장부가 반영했는가)', '담보·전환사채·covenant 의 주가 연동 트리거 점검', '수주·계약 파이프라인 실사']
          : ['조달 창구가 열려 있을 때 선제 조달 검토 (증자·회사채 여건)', '현금흐름 악화 원인 분해 (운전자본 vs 수익성)', '12개월 유동성 계획과 시장 조달 계획 통합'],
      });
    }
  }

  // ── E7: 공시·주가 정합 (해명·풍문 공시 직전 이상 변동) ──────
  {
    const id = 'E7';
    const name = '공시·주가 정합 (해명 공시 전 이상 변동)';
    const categoryPattern = /해명\s*공시.*(주가|거래량)|(주가|거래량).*(선행|이상\s*변동|급변)|정보\s*(유출|관리)\s*리스크|불공정\s*거래/;
    const events = clarificationEvents(disclosures);
    if (events.length === 0) {
      signals.push({
        id, name, present: false, evaluable: true,
        note: '관찰 창 내 해명·풍문 공시 없음 — 대사 대상 없음',
        evidence: { windowDays: days, clarificationCount: 0, events: [] },
        markers: [], categoryPattern,
        checkHints: [],
      });
    } else if (valid.length < t.preWindowDays + 3) {
      signals.push(notEvaluable(id, name, categoryPattern, '시세 데이터 부족 — 해명 공시 전 변동을 대사할 수 없음'));
    } else {
      const judged = events.map((ev) => {
        const before = valid.filter((p) => p.basDt < ev.date);
        const pre = before.slice(-t.preWindowDays);
        const base = before.slice(-(t.preWindowDays + t.baseWindowDays), -t.preWindowDays);
        if (pre.length < 2) return { ...ev, preMovePct: null, volumeRatio: null, abnormal: false, note: '공시 전 시세 부족' };
        const preMovePct = growthPct(pre[0].close, pre[pre.length - 1].close);
        const avg = (rows) => {
          const vols = rows.map((p) => p.volume).filter((v) => v !== null);
          return vols.length ? vols.reduce((a, b) => a + b, 0) / vols.length : null;
        };
        const preVol = avg(pre);
        const baseVol = avg(base);
        const volumeRatio = preVol !== null && baseVol ? preVol / baseVol : null;
        const abnormal = (preMovePct !== null && Math.abs(preMovePct) >= t.preMovePct)
          || (volumeRatio !== null && volumeRatio >= t.volumeSpikeRatio);
        return { ...ev, preMovePct: round1(preMovePct), volumeRatio: volumeRatio === null ? null : Math.round(volumeRatio * 100) / 100, abnormal };
      });
      const abnormalEvents = judged.filter((e) => e.abnormal);
      const present = abnormalEvents.length > 0;
      const lead = abnormalEvents[0];
      signals.push({
        id, name, present, evaluable: true, note: '',
        evidence: {
          windowDays: days,
          clarificationCount: events.length,
          abnormalCount: abnormalEvents.length,
          events: judged,
        },
        markers: present
          ? [
            ...(lead.preMovePct !== null && Math.abs(lead.preMovePct) >= t.preMovePct ? [pctMarker(lead.preMovePct)] : []),
            ...(lead.volumeRatio !== null && lead.volumeRatio >= t.volumeSpikeRatio ? [new RegExp(`(?<!\\d)${String(lead.volumeRatio).replace('.', '[.,]')}(?!\\d)`)] : []),
            /해명|풍문/, /주가|거래량/,
          ]
          : [],
        categoryPattern,
        checkHints: present
          ? ['해명 공시 대상 보도의 최초 유통 경로 확인 (내부 정보 접점 점검)', '공시 전 이상 변동 구간의 대량 매매 주체 확인 (필요 시 시장감시 조회)', '중요 정보 관리 규정·공시 승인 체인 점검']
          : [],
      });
    }
  }

  return signals;
}
