import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';
import { deriveMarketSignals, clarificationEvents, MARKET_THRESHOLDS } from '../../common/market-signals.mjs';
import { evaluateBriefing, signalsFromPacket } from '../briefing-eval.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIAG_CLI = join(HERE, '..', '..', 'bin', 'diagnose-company.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

// ── 시세 픽스처 빌더 ─────────────────────────────────────────
// n 거래일 시세: 시총 startCap→endCap 선형, 종가/거래량 상수 (필요 시 오버라이드).
function makePrices({ n = 40, startCap = 10e12, endCap = 10e12, close = 100, volume = 1000, overrides = {} } = {}) {
  return Array.from({ length: n }, (_, i) => {
    const basDt = String(20260401 + i); // 단순 증가 문자열 날짜 (정렬만 유효하면 됨)
    const cap = startCap + ((endCap - startCap) * i) / (n - 1);
    return { basDt, close, volume, marketCap: cap, ...(overrides[i] ?? {}) };
  });
}

const OCF_STABLE = [900, 1000, 1000];   // 성장률 0%
const OCF_DROPPED = [900, 1000, 600];   // -40%

// ── E6 ───────────────────────────────────────────────────────
test('E6 — 시총 급락 + OCF 안정 → PRESENT (market-drop 모드)', () => {
  const prices = makePrices({ startCap: 10e12, endCap: 6.5e12 }); // -35%
  const [e6] = deriveMarketSignals({ prices, series: { ocf: OCF_STABLE }, disclosures: [] });
  assert.equal(e6.id, 'E6');
  assert.equal(e6.present, true);
  assert.equal(e6.evidence.mode, 'market-drop');
  assert.equal(e6.evidence.marketCapChangePct, -35);
  // 마커는 계산값에서 생성 — 시총 변화율이 매칭돼야 한다
  assert.ok(e6.markers.some((m) => m.test('시가총액이 35.0% 급락')));
});

test('E6 — OCF 급감 + 시총 유지 → PRESENT (book-drop 모드)', () => {
  const prices = makePrices({ startCap: 10e12, endCap: 9.8e12 }); // -2%
  const [e6] = deriveMarketSignals({ prices, series: { ocf: OCF_DROPPED }, disclosures: [] });
  assert.equal(e6.present, true);
  assert.equal(e6.evidence.mode, 'book-drop');
  assert.equal(e6.evidence.ocfGrowthPct, -40);
});

test('E6 — 시총·OCF 같은 방향(동반 하락/안정)이면 absent', () => {
  // 동반 급락: 시장이 장부를 반영 중 — 괴리 아님
  const both = deriveMarketSignals({
    prices: makePrices({ startCap: 10e12, endCap: 6.5e12 }),
    series: { ocf: OCF_DROPPED }, disclosures: [],
  })[0];
  assert.equal(both.present, false);
  // 둘 다 안정
  const calm = deriveMarketSignals({
    prices: makePrices(), series: { ocf: OCF_STABLE }, disclosures: [],
  })[0];
  assert.equal(calm.present, false);
  assert.equal(calm.evaluable, true);
});

test('E6 — 시세 부족/OCF 결측이면 evaluable=false (지어내지 않음)', () => {
  const noPrices = deriveMarketSignals({ prices: [], series: { ocf: OCF_STABLE }, disclosures: [] })[0];
  assert.equal(noPrices.evaluable, false);
  const noOcf = deriveMarketSignals({ prices: makePrices(), series: {}, disclosures: [] })[0];
  assert.equal(noOcf.evaluable, false);
});

// ── E7 ───────────────────────────────────────────────────────
const CLAR = [{ report_nm: '풍문또는보도에대한해명(미확정)', rcept_dt: '20260435' }]; // 픽스처 날짜 인덱스 34 이후

test('clarificationEvents — 해명·풍문 공시만 추출', () => {
  const events = clarificationEvents([
    { report_nm: '분기보고서', rcept_dt: '20260401' },
    { report_nm: '풍문또는보도에대한해명(미확정)', rcept_dt: '20260410' },
    { report_nm: '[기재정정]사업보고서', rcept_dt: 'bad-date' },
  ]);
  assert.equal(events.length, 1);
  assert.equal(events[0].date, '20260410');
});

test('E7 — 해명 공시 직전 주가 선행 급변 → PRESENT', () => {
  // 공시(인덱스 34) 직전 5거래일(29~33) 종가 100→112 (+12%, 임계 8% 초과)
  const overrides = { 29: { close: 100 }, 30: { close: 103 }, 31: { close: 106 }, 32: { close: 109 }, 33: { close: 112 } };
  const prices = makePrices({ overrides });
  const [, e7] = deriveMarketSignals({ prices, series: { ocf: OCF_STABLE }, disclosures: CLAR });
  assert.equal(e7.id, 'E7');
  assert.equal(e7.present, true);
  assert.equal(e7.evidence.abnormalCount, 1);
  assert.equal(e7.evidence.events[0].preMovePct, 12);
  assert.ok(e7.markers.some((m) => m.test('공시 전 5거래일 12.0% 상승')));
});

test('E7 — 직전 거래량 급증(2배+)만으로도 PRESENT', () => {
  const overrides = {};
  for (let i = 29; i <= 33; i += 1) overrides[i] = { volume: 2500 }; // 기준 1000 대비 2.5배
  const prices = makePrices({ overrides });
  const [, e7] = deriveMarketSignals({ prices, series: { ocf: OCF_STABLE }, disclosures: CLAR });
  assert.equal(e7.present, true);
  assert.equal(e7.evidence.events[0].volumeRatio, 2.5);
});

test('E7 — 해명 공시가 없으면 대사 대상 없음(absent·evaluable) / 시세 부족이면 N/A', () => {
  const none = deriveMarketSignals({ prices: makePrices(), series: { ocf: OCF_STABLE }, disclosures: [] })[1];
  assert.equal(none.present, false);
  assert.equal(none.evaluable, true);
  assert.match(none.note, /대사 대상 없음/);
  const short = deriveMarketSignals({ prices: makePrices({ n: 3 }), series: { ocf: OCF_STABLE }, disclosures: CLAR })[1];
  assert.equal(short.evaluable, false);
});

test('E7 — 정상 변동(임계 미만)이면 absent + 이벤트 판정 기록', () => {
  const [, e7] = deriveMarketSignals({ prices: makePrices(), series: { ocf: OCF_STABLE }, disclosures: CLAR });
  assert.equal(e7.present, false);
  assert.equal(e7.evidence.clarificationCount, 1);
  assert.equal(e7.evidence.events[0].abnormal, false);
});

// ── 채점기 연동: E6 도 packet 왕복 후 채점 성립 ──────────────
test('E6 — 직렬화 왕복 후 briefing-eval 재현율 채점 성립', () => {
  const prices = makePrices({ startCap: 10e12, endCap: 6.5e12 });
  const derived = deriveMarketSignals({ prices, series: { ocf: OCF_STABLE }, disclosures: [] });
  const packet = { signals: derived.map((s) => ({ ...s, markers: s.markers.map((m) => m.source), categoryPattern: s.categoryPattern.source })) };
  const hydrated = signalsFromPacket(packet);
  const briefing = `# 진단
## 1. 시장·현금흐름 괴리
결론: 시가총액이 90일간 35.0% 하락했으나 영업현금흐름은 안정적입니다.
근거: 시총 10→6.5조, 변화율 -35.0%, 영업현금흐름 성장률 0%.
확신도: 가설 — 시장이 반영한 정보의 실체 확인 필요.
판별 테스트: 급락 구간 뉴스·공시 이벤트 대조.
권고 조치: 담보·covenant 주가 트리거 점검.
`;
  const r = evaluateBriefing(briefing, { signals: hydrated });
  assert.equal(r.signals.find((s) => s.id === 'E6').detected, true);
});

// ── diagnose CLI 통합 (fetch 스텁 — 네트워크 0) ──────────────
const row = (sj, id, nm, [a0, a1, a2]) => ({
  sj_div: sj, account_id: id, account_nm: nm,
  bfefrmtrm_amount: String(a0), frmtrm_amount: String(a1), thstrm_amount: String(a2),
});

test('diagnose-company --with-market: E6·E7 이 패킷 signals 에 실린다', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-mkt-'));
  try {
    // 시총 -35% 급락 + OCF 안정 → E6 PRESENT 시나리오
    const item = Array.from({ length: 30 }, (_, i) => ({
      basDt: String(20260601 + i), srtnCd: '123456', clpr: '100', trqu: '1000',
      mrktTotAmt: String(Math.round(10e12 - (3.5e12 * i) / 29)),
    }));
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify({
      rules: [
        { match: 'getStockPriceInfo', json: { response: { header: { resultCode: '00' }, body: { items: { item } } } } },
        { match: 'fnlttSinglAcntAll.json', json: { status: '000', list: [
          row('IS', 'ifrs-full_Revenue', '매출액', [800, 900, 1000]),
          row('IS', 'dart_OperatingIncomeLoss', '영업이익', [80, 90, 100]),
          row('CF', 'ifrs-full_CashFlowsFromUsedInOperatingActivities', '영업활동현금흐름', [120, 135, 150]),
        ] } },
        { match: 'company.json', json: { status: '000', corp_name: '테스트전자(주)', stock_code: '123456', ceo_nm: '홍길동', bizr_no: '1234567890' } },
        { match: 'list.json', json: { status: '000', total_count: 1, list: [{ report_nm: '분기보고서', rcept_dt: '20260615' }] } },
      ],
    }));
    const r = runNode([DIAG_CLI, '--corp-code', '00000001', '--year', '2025', '--with-market', '--json'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key', KRX_API_KEY: 'test-key' }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const out = JSON.parse(r.stdout);
    assert.equal(out.market.enabled, true);
    assert.equal(out.market.tradingDays, 30);
    const ids = out.signals.map((s) => s.id);
    assert.ok(ids.includes('E6') && ids.includes('E7'));
    const e6 = out.signals.find((s) => s.id === 'E6');
    assert.equal(e6.present, true);
    assert.equal(e6.evidence.mode, 'market-drop');
    // 사람용 출력에도 시장 축 섹션이 보인다
    const human = runNode([DIAG_CLI, '--corp-code', '00000001', '--year', '2025', '--with-market'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key', KRX_API_KEY: 'test-key' }));
    assert.match(human.stdout, /\[시장 축 — 주가·시총 대사/);
    assert.match(human.stdout, /E6 시장·현금흐름 괴리/);
    assert.match(human.stdout, /투자 판단 아님/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('diagnose-company --with-market: KRX 실패 시 시장 축만 생략 (전체 진단은 계속)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-mkt2-'));
  try {
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify({
      rules: [ // getStockPriceInfo 규칙 없음 → 599 → 시장 축 disabled 경로
        { match: 'fnlttSinglAcntAll.json', json: { status: '000', list: [
          row('IS', 'ifrs-full_Revenue', '매출액', [800, 900, 1000]),
          row('CF', 'ifrs-full_CashFlowsFromUsedInOperatingActivities', '영업활동현금흐름', [120, 135, 150]),
        ] } },
        { match: 'company.json', json: { status: '000', corp_name: '테스트전자(주)', stock_code: '123456', ceo_nm: '홍길동', bizr_no: '1234567890' } },
        { match: 'list.json', json: { status: '000', total_count: 0, list: [] } },
      ],
    }));
    const r = runNode([DIAG_CLI, '--corp-code', '00000001', '--year', '2025', '--with-market', '--json'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key', KRX_API_KEY: 'test-key' }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const out = JSON.parse(r.stdout);
    assert.equal(out.market.enabled, false);
    assert.ok(!out.signals.some((s) => s.id === 'E6')); // 실패 시 신호 미추가 (evaluable 오염 방지)
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── 임계값 계약 ──────────────────────────────────────────────
test('MARKET_THRESHOLDS — 키 존재 + 오버라이드 병합', () => {
  for (const key of ['mcapDropPct', 'ocfStablePct', 'ocfDropPct', 'mcapStablePct', 'preMovePct', 'volumeSpikeRatio']) {
    assert.ok(Number.isFinite(MARKET_THRESHOLDS[key]), key);
  }
  const prices = makePrices({ startCap: 10e12, endCap: 8.5e12 }); // -15%
  const strict = deriveMarketSignals({ prices, series: { ocf: OCF_STABLE }, disclosures: [] }, { mcapDropPct: 10 })[0];
  assert.equal(strict.present, true); // 임계 완화 오버라이드가 반영된다
});

test('E6 categoryPattern — E7 절의 "주가 하락" 서술에 오매칭되지 않는다 (삼성전자 라이브 회귀)', () => {
  const [e6] = deriveMarketSignals({ prices: makePrices(), series: { ocf: OCF_STABLE }, disclosures: [] });
  const e7RiskSection = '리스크 2. 공시·주가 정합 — 해명 공시 직전 5거래일 주가가 10.5% 하락했습니다. 정보 관리 리스크.';
  assert.equal(e6.categoryPattern.test(e7RiskSection), false); // 주가 하락 ≠ E6 주제
  assert.equal(e6.categoryPattern.test('시가총액이 급락해 시장 평가와 괴리'), true);
  assert.equal(e6.categoryPattern.test('시장 평가와 현금흐름의 괴리'), true);
});
