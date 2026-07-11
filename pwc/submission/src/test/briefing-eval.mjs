#!/usr/bin/env node
/**
 * 브리핑 자동 채점 (briefing-eval) — README 의 검증 기준을 실행 가능하게 만든다.
 *
 * v3 — 정답지를 코드에 하드코딩하지 않는다. 채점 기준(신호·마커·근거 수치)은
 * common/signals.mjs 가 "채점 대상 브리핑이 근거로 삼은 바로 그 데이터"에서 파생한다.
 * 따라서 어떤 회사의 데이터 폴더를 --data-dir 로 지정해도 같은 절차로 채점된다.
 * (에이전트용 신호 조회는 bin/detect-signals.mjs — 채점기와 동일 엔진을 공유)
 *
 * 채점 축:
 *   1. 재현율(+근접성) — 데이터에서 파생된 PRESENT 신호를 포착했는가.
 *      신호별 마커(파생 수치) 2개 이상이 "같은 섹션(##/### 블록) 안"에서 함께 나와야
 *      인정한다 (숫자를 흩뿌리면 불인정 — 한 가설로 수렴했는지를 근사).
 *   2. 정밀도/오탐 — 데이터에 없는(absent) 신호를 리스크로 주장하거나,
 *      근거 마커 0개인 리스크를 "확인됨"으로 단정하지 않았는가.
 *   3. 확신도 보정 — 포착한 신호마다 확신도 태그를 달았는가.
 *   4. 판별 테스트 — 포착한 신호마다 "확인해야 할 데이터/절차"를 명시했는가.
 *   5. 표현 안전성 — 단정 표현("분식입니다", "확실합니다" 등)을 쓰지 않았는가.
 *   6. 구조 — 결론 / 근거 / 확신도 / 권고 조치 섹션을 갖췄는가.
 *
 * 모드:
 *   normal(기본) — PRESENT 신호 전부 포착 + absent 신호 미주장 이면 PASS.
 *                  데이터에 PRESENT 신호가 0건이면 자동으로 음성 채점으로 전환된다.
 *   clean        — 명시적 음성 채점. 어떤 신호든 "발견했다고 지어내면" FAIL.
 *
 * 사용:
 *   node test/briefing-eval.mjs <briefing.md>                       # 동봉 샘플 데이터 기준
 *   node test/briefing-eval.mjs --data-dir <회사데이터폴더> <briefing.md>
 *   node test/briefing-eval.mjs --signals-file <진단패킷.json> <briefing.md>
 *                                # diagnose-company --json 출력(외부 신호 E1~E5)을 정답지로 채점
 *   node test/briefing-eval.mjs --clean <briefing.md>               # 음성(clean) 채점
 *   node test/briefing-eval.mjs --judge <briefing.md>               # 규칙 PASS 후 LLM Judge(인과 품질, advisory)
 *   node test/briefing-eval.mjs --json <briefing.md>                # 기계가 읽는 JSON
 *   node test/briefing-eval.mjs --self-test                         # 채점기 자체 회귀 테스트
 *
 * --judge: 1차 규칙 채점(결정론)이 PASS 인 양성 브리핑에 대해서만 LLM 이 "왜 문제인가"의
 * 인과 품질(causality/decision/falsifiability, 각 0~2)을 판정한다. advisory — PASS/FAIL 은
 * 규칙 채점이 결정하며 Judge 는 점수·심사평만 낸다. 키 없으면 자동 생략 (common/judge.mjs).
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadBooks, resolveDataDir } from '../common/books.mjs';
import { deriveSignals, loadThresholds } from '../common/signals.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SAMPLE_DIR = join(HERE, '..', 'data', 'sample');
const CLEAN_FIXTURE_DIR = join(HERE, '..', 'data', 'fixtures', 'clean');

const MIN_MARKERS_PER_SIGNAL = 2;

/** 데이터 디렉터리에서 채점용 신호(정답지)를 파생한다. */
export function signalsForDataDir(dataDir) {
  return deriveSignals(loadBooks(dataDir), loadThresholds(dataDir));
}

let defaultSignalsCache = null;
function defaultSignals() {
  defaultSignalsCache ??= signalsForDataDir(SAMPLE_DIR);
  return defaultSignalsCache;
}

/**
 * 직렬화된 진단 패킷(JSON)에서 채점용 신호를 복원한다.
 * detect-signals/diagnose-company 의 --json 출력은 markers/categoryPattern 을
 * 정규식 소스 문자열로 직렬화하므로, 여기서 RegExp 로 되살린다.
 */
export function signalsFromPacket(parsed) {
  const arr = Array.isArray(parsed) ? parsed : parsed.signals ?? [];
  if (!Array.isArray(arr) || arr.length === 0) {
    throw new Error('진단 패킷에 signals 배열이 없습니다 — detect-signals/diagnose-company 의 --json 출력을 지정하세요.');
  }
  return arr.map((s) => ({
    ...s,
    markers: (s.markers ?? []).map((src) => new RegExp(src)),
    categoryPattern: new RegExp(s.categoryPattern ?? '$^'),
  }));
}

// 표현 안전성 가드 — 가설-확신도-확인절차 구조를 깨는 단정 표현.
const FORBIDDEN = [
  { pattern: /분식\s*(회계)?\s*(입니다|이다|였다|임이\s*확실)/, label: '분식 단정' },
  { pattern: /확실합니다|확실하다/, label: '확실 단정' },
  { pattern: /명백한|명백히|명백하다/, label: '명백 단정' },
  { pattern: /틀림없/, label: '틀림없다 단정' },
  { pattern: /단언(하|컨대)/, label: '단언 표현' },
];

const REQUIRED_SECTIONS = [
  { pattern: /결론/, label: '결론' },
  { pattern: /근거|증거/, label: '근거/증거' },
  { pattern: /확신도/, label: '확신도' },
  { pattern: /권고\s*조치/, label: '권고 조치' },
];

// 확신도 태그 / 확인됨(단정) / 판별 테스트 감지.
const CONFIDENCE_TAG = /확신도|가설|가능성|확인\s*필요|확인됨|추가\s*확인/;
const CONFIRMED_TAG = /확인됨/;
const DISCRIMINATION = /판별\s*테스트|확인\s*(필요|절차|해야)|대조|재산정|재계산\s*검증|추가\s*데이터/;
// 음성 브리핑에서 "이상 없음"을 인정한다는 신호.
const CLEAN_ACK = /이상\s*없|이상\s*신호[가는]?\s*(확인되지|없|나타나지)|특이사항\s*없|유의미한\s*이상[^가-힣]*(없|아님)|리스크[가는은]?[^.\n]{0,30}(포착|확인|감지)되지\s*않|리스크\s*(항목|신호)[이가은는]?\s*없|발화(\(PRESENT\))?[된한]?[^.\n]{0,20}(항목|신호|안건|리스크)[은는이가]?\s*(0\s*건|없)/;
// "미발화·임계값 미달" 로 명시 서술된 절은 리스크 주장이 아니다 — 미발화 신호의
// 정성 요약(투명성)까지 오탐으로 잡으면 좋은 브리핑을 벌점 주게 된다.
const ABSENT_ACK = /미발화|발화하지\s*않|임계값\s*미달|기준(치|값)?\s*미달|PRESENT\s*(아님|가?\s*아니)/;
// 과잉 확신·오탐 검사에서 제외할 비(非)리스크 섹션 heading.
const NON_RISK_HEADING = /확인\s*범위|범위|한계|요약|summary|헤드라인|headline|점검\s*결과|목차|부록/i;
// 리스크 주장 문맥 — absent 신호의 카테고리 언급을 오탐으로 볼 조건.
const RISK_CONTEXT = /리스크|위험|손실|적자|의심|우려|가능성|문제/;

/**
 * 마크다운을 heading(#~######) 기준 섹션으로 분할한다.
 * 각 섹션은 { heading, text } — text 는 heading 줄 포함 본문.
 */
export function splitSections(text) {
  const lines = text.split(/\r?\n/);
  const sections = [];
  let cur = { heading: '(preamble)', body: [] };
  for (const line of lines) {
    if (/^#{1,6}\s/.test(line)) {
      sections.push(cur);
      cur = { heading: line.replace(/^#{1,6}\s*/, '').trim(), body: [line] };
    } else {
      cur.body.push(line);
    }
  }
  sections.push(cur);
  return sections
    .map((s) => ({ heading: s.heading, text: s.body.join('\n') }))
    .filter((s) => s.text.trim().length > 0);
}

/**
 * 브리핑 텍스트를 채점한다.
 * @param {string} text
 * @param {{ mode?: 'normal'|'clean', signals?: ReturnType<typeof signalsForDataDir> }} opts
 *   signals 를 생략하면 동봉 샘플 데이터에서 파생한다.
 */
export function evaluateBriefing(text, opts = {}) {
  const allSignals = opts.signals ?? defaultSignals();
  const mode = opts.mode === 'clean' ? 'clean' : 'normal';
  const sections = splitSections(text);

  const presentSignals = allSignals.filter((s) => s.present);
  // clean 모드는 "모든 신호를 주장하면 안 됨", normal 모드는 absent 신호만 주장 금지.
  const negative = mode === 'clean' || presentSignals.length === 0;

  // (1) 재현율 + 근접성: 한 섹션 안에서 마커가 MIN_MARKERS 이상 함께 나와야 포착으로 인정.
  const signals = allSignals.map((signal) => {
    // 마커 최다 섹션을 신호의 서술 위치로 본다. 동률이면 확신도 태그가 있는 절을
    // 우선한다 — 요약이 리스크 절과 같은 수치를 인용해도 본문 절이 채점 대상이 되도록.
    let best = { hitCount: 0, section: null, tagged: false };
    for (const sec of sections) {
      const hits = signal.markers.filter((m) => m.test(sec.text)).length;
      const tagged = CONFIDENCE_TAG.test(sec.text);
      if (hits > best.hitCount || (hits > 0 && hits === best.hitCount && tagged && !best.tagged)) {
        best = { hitCount: hits, section: sec, tagged };
      }
    }
    const detected = best.hitCount >= MIN_MARKERS_PER_SIGNAL;
    const secText = best.section ? best.section.text : '';
    // 카테고리 주장 감지 — 리스크 문맥의 섹션에서 신호 유형을 리스크로 서술 ("이상 없음" 승인 섹션 제외).
    const claimed = sections.some((sec) =>
      signal.categoryPattern.test(sec.text)
      && RISK_CONTEXT.test(sec.text)
      && !CLEAN_ACK.test(sec.text)
      && !ABSENT_ACK.test(sec.text)
      && !NON_RISK_HEADING.test(sec.heading));
    return {
      id: signal.id,
      name: signal.name,
      present: signal.present,
      hitCount: best.hitCount,
      detected,
      claimed,
      section: best.section ? best.section.heading : null,
      // (3)(4) 확신도 태그 / 판별 테스트는 포착된 신호의 섹션 안에 있어야 한다.
      confidenceTagged: detected ? CONFIDENCE_TAG.test(secText) : false,
      hasDiscriminationTest: detected ? DISCRIMINATION.test(secText) : false,
    };
  });

  const detectedPresent = signals.filter((s) => s.present && s.detected);
  const recall = detectedPresent.length;

  // (3) 확신도 보정 / (4) 판별 테스트 누락 — 포착한 신호에 대해서만 검사.
  const calibrationMisses = detectedPresent.filter((s) => !s.confidenceTagged).map((s) => s.id);
  const discriminationMisses = detectedPresent.filter((s) => !s.hasDiscriminationTest).map((s) => s.id);

  // (2a) 오탐: 주장하면 안 되는 신호(negative: 전부 / normal: absent)를 포착·주장.
  const forbiddenSignals = negative ? signals : signals.filter((s) => !s.present);
  const falseSignals = forbiddenSignals.filter((s) => s.detected || s.claimed).map((s) => s.id);

  // (2b) 과잉 확신(정밀도): 근거 마커 0개인데 "확인됨"으로 단정한 리스크 섹션.
  const overClaims = sections
    .filter((sec) => {
      if (NON_RISK_HEADING.test(sec.heading)) return false;
      if (!CONFIRMED_TAG.test(sec.text)) return false;
      if (CLEAN_ACK.test(sec.text)) return false; // "확인됨 — 이상 없음" 은 리스크 단정이 아님
      const anyMarker = allSignals.some((sig) => sig.markers.some((m) => m.test(sec.text)));
      const riskLike = /결론|리스크|손실|적자|위험|risk/i.test(sec.text) || /^\s*\d+\./.test(sec.heading);
      return riskLike && !anyMarker;
    })
    .map((sec) => sec.heading);

  // (5) 표현 안전성 / (6) 구조.
  const violations = FORBIDDEN.filter(({ pattern }) => pattern.test(text)).map(({ label }) => label);
  const missingSections = REQUIRED_SECTIONS.filter(({ pattern }) => !pattern.test(text)).map(({ label }) => label);

  // 음성 채점: 이상 없음 승인이 있어야 한다.
  const cleanAck = CLEAN_ACK.test(text);

  let pass;
  if (negative) {
    pass =
      falseSignals.length === 0 &&
      overClaims.length === 0 &&
      violations.length === 0 &&
      cleanAck;
  } else {
    pass =
      recall === presentSignals.length &&
      falseSignals.length === 0 &&
      violations.length === 0 &&
      missingSections.length === 0 &&
      overClaims.length === 0 &&
      calibrationMisses.length === 0 &&
      discriminationMisses.length === 0;
  }

  return {
    mode,
    negative,
    recall,
    total: presentSignals.length,
    signals,
    violations,
    missingSections,
    overClaims,
    calibrationMisses,
    discriminationMisses,
    falseSignals,
    cleanAck,
    pass,
  };
}

function printReport(result) {
  console.log(`=== 브리핑 채점 (briefing-eval${result.mode === 'clean' ? ' · clean' : ''}) ===`);

  if (result.negative) {
    console.log(`\n[오탐] 지어낸 신호 ${result.falseSignals.length}건${result.falseSignals.length ? ` (${result.falseSignals.join(', ')})` : ''}`);
    console.log(`[이상 없음 승인] ${result.cleanAck ? '있음' : '없음'}`);
  } else {
    console.log(`\n[재현율] ${result.recall}/${result.total}`);
    for (const s of result.signals.filter((x) => x.present)) {
      const where = s.section ? ` @${s.section}` : '';
      console.log(`${s.detected ? '  ok' : 'MISS'}  ${s.id} ${s.name} (마커 ${s.hitCount}개${where})`);
    }
    if (result.falseSignals.length > 0) {
      console.log(`\n[오탐] 데이터에 없는 신호 주장: ${result.falseSignals.join(', ')}`);
    }
    console.log(`\n[확신도 태깅] ${result.calibrationMisses.length === 0 ? '포착 신호 모두 태깅됨' : `누락: ${result.calibrationMisses.join(', ')}`}`);
    console.log(`[판별 테스트] ${result.discriminationMisses.length === 0 ? '포착 신호 모두 존재' : `누락: ${result.discriminationMisses.join(', ')}`}`);
    console.log(`[구조] ${result.missingSections.length === 0 ? '필수 섹션 모두 존재' : `누락: ${result.missingSections.join(', ')}`}`);
  }

  console.log(`\n[정밀도/과잉 확신] ${result.overClaims.length === 0 ? '위반 없음' : `위반 ${result.overClaims.length}건`}`);
  for (const h of result.overClaims) console.log(`FAIL  근거 없는 확인됨 단정: ${h}`);

  console.log(`\n[표현 안전성] ${result.violations.length === 0 ? '위반 없음' : `위반 ${result.violations.length}건`}`);
  for (const v of result.violations) console.log(`FAIL  단정 표현: ${v}`);

  console.log(result.pass ? '\nEVAL PASS' : '\nEVAL FAIL');
}

// ── self-test 픽스처 ──────────────────────────────────────────────
// 수치는 동봉 샘플 데이터(data/sample)에서 파생되는 값과 일치해야 한다 — 채점기가
// 그 수치를 코드에 저장하지 않고 매 실행 파생한다는 것 자체가 self-test 의 검증 대상.
const GOOD = `# CEO Briefing — 회계 리스크 진단

## 헤드라인 요약
- 수익 인식·거래처 집중·원가 왜곡·금리 노출 4건.

## 1. 수익 조기 인식 가능성
결론: 매출은 11.3% 늘었지만 현금이 따라오지 않았습니다.
근거: 매출채권 +46.9%, 계약부채 340→180, 영업현금흐름 880→-310.
왜 문제인가: 팔았다고 기록했지만 현금이 들어오지 않는 매출일 수 있습니다.
확신도: 가설 — 기말 전후 매출 전표와 계약 진행률 확인 필요.
판별 테스트: 기말 ±7일 매출 전표 분포와 계약 진행률 데이터 대조 (재무팀).

## 2. 특정 거래처 신용 집중
결론: 장기 미수가 거래처 한 곳에 쏠려 있습니다.
근거: 90일 초과 채권 중 에이프릴리테일 1,750, 비중 91.1%.
왜 문제인가: 전사 회수 문제가 아니라 특정 거래처 신용 리스크입니다.
확신도: 가설 — 해당 거래처 여신 한도·담보 설정 내역 확인 필요.
판별 테스트: 에이프릴리테일 여신계약·담보등기 대조 (영업·재무팀).

## 3. 원가 배분 왜곡 — 제품C의 보이지 않는 손실
결론: 제품C는 자원 소비 기준으로 재배부하면 적자로 뒤집힙니다.
근거: 기계시간 45% 기준 재배부 시 공통원가 1,350, 영업손익 +20 → -880.
왜 문제인가: 매출 비중 배부가 실제 기계시간을 반영하지 못해 손실이 가려집니다.
확신도: 확인됨 — 배부 기준 재계산은 산술로 확정됩니다.
판별 테스트: 기계시간 로그 원천 데이터로 배부율 재산정 검증 (원가팀).

## 4. 차입 의존 성장·금리 노출
결론: 영업현금 악화를 변동금리 차입으로 메우고 있습니다.
근거: 변동금리 차입 14,500, 이자비용 90→320, 이자보상배율 23.9→8.9배.
왜 문제인가: 금리 상승 구간에서 이자 부담이 가속됩니다.
확신도: 가설 — 고정금리 전환·헤지(IRS) 계약 여부 확인 필요.
판별 테스트: 차입 약정서 금리 조건과 헤지 계약 현황 대조 (재무팀).

## 확인 범위와 한계
재고 회전과 판관비는 검토했고 특이사항 없었습니다.
권고 조치: 위 4건을 재무·원가·영업팀에 배정해 1주 내 판별 테스트 결과 회신.
`;

// 마커는 있으나 문서 전체에 흩뿌려 한 섹션에 2개가 모이지 않음 → 근접성 실패.
const SCATTERED = `# 보고
## A
결론: 매출채권 46.9% 증가.
## B
근거: 매출 11.3% 증가.
## C
확신도: 영업현금흐름 -310 전환.
## D
권고 조치: 계약부채 340→180 확인.
`;

// 이상이 없는 데이터에 대한 정상 브리핑 (음성 채점 PASS 기대).
const CLEAN_GOOD = `# CEO Briefing — 점검 결과
## 확인 범위
매출·채권·원가·차입 4개 축을 교차 대조했습니다.
결론: 유의미한 이상 신호가 확인되지 않았습니다.
근거: aging 합계가 시산표 매출채권과 일치하고 공통원가 부담이 자원 소비와 정합적입니다.
확신도: 확인됨 — 불변식 게이트 GATE PASS.
권고 조치: 다음 분기 동일 점검 유지.
`;

// 이상이 없는데 리스크를 지어냄 (음성 채점 FAIL 기대 — 오탐 + 과잉 확신).
const CLEAN_BAD = `# 보고
## 1. 원가 손실
근거: 제품C -880 적자, 미수 91.1% 에이프릴리테일 집중 리스크.
확신도: 확인됨.
## 2. 세무 리스크
결론: 세금 추징이 있을 것으로 확인됨.
확신도: 확인됨.
`;

function selfTest() {
  let failures = 0;
  const assert = (name, cond) => {
    console.log(`${cond ? '  ok' : 'FAIL'}  ${name}`);
    if (!cond) failures += 1;
  };

  console.log('=== briefing-eval self-test ===');

  // 프레임워크: 정답지가 데이터에서 파생되는지부터 검증.
  const sampleSignals = signalsForDataDir(SAMPLE_DIR);
  const cleanSignals = signalsForDataDir(CLEAN_FIXTURE_DIR);
  assert('파생: 샘플 데이터 → PRESENT 4건', sampleSignals.filter((s) => s.present).length === 4);
  assert('파생: clean 픽스처 → PRESENT 0건', cleanSignals.filter((s) => s.present).length === 0);

  const good = evaluateBriefing(GOOD, { signals: sampleSignals });
  const bad = evaluateBriefing('# 보고\n매출채권 급증은 명백한 분식회계입니다. 확실합니다. 제품C -880. 기계시간 45%.', { signals: sampleSignals });
  const scattered = evaluateBriefing(SCATTERED, { signals: sampleSignals });
  const cleanGood = evaluateBriefing(CLEAN_GOOD, { mode: 'clean', signals: cleanSignals });
  const cleanBad = evaluateBriefing(CLEAN_BAD, { mode: 'clean', signals: cleanSignals });
  const cleanAuto = evaluateBriefing(CLEAN_GOOD, { signals: cleanSignals }); // normal 모드 → 자동 음성 전환

  assert('정상 브리핑: 재현율 4/4', good.recall === 4 && good.total === 4);
  assert('정상 브리핑: 단정 표현 없음', good.violations.length === 0);
  assert('정상 브리핑: 필수 섹션 충족', good.missingSections.length === 0);
  assert('정상 브리핑: 확신도 태깅 완비', good.calibrationMisses.length === 0);
  assert('정상 브리핑: 판별 테스트 완비', good.discriminationMisses.length === 0);
  assert('정상 브리핑: 과잉 확신 없음', good.overClaims.length === 0);
  assert('정상 브리핑: 종합 PASS', good.pass === true);

  assert('불량 브리핑: 신호 미포착', bad.recall < 4);
  assert('불량 브리핑: 단정 표현 감지', bad.violations.length >= 2);
  assert('불량 브리핑: 종합 FAIL', bad.pass === false);

  assert('근접성: 흩뿌린 마커는 미포착 (S1 detected=false)', scattered.signals.find((s) => s.id === 'S1').detected === false);
  assert('근접성: 종합 FAIL', scattered.pass === false);

  assert('clean 정상: 오탐 0', cleanGood.falseSignals.length === 0);
  assert('clean 정상: 이상 없음 승인 인식', cleanGood.cleanAck === true);
  assert('clean 정상: 종합 PASS', cleanGood.pass === true);
  assert('clean 자동 전환: PRESENT 0건이면 normal 도 음성 채점', cleanAuto.negative === true && cleanAuto.pass === true);

  assert('clean 불량: 오탐 감지 (지어낸 신호)', cleanBad.falseSignals.length >= 1);
  assert('clean 불량: 과잉 확신 감지', cleanBad.overClaims.length >= 1);
  assert('clean 불량: 종합 FAIL', cleanBad.pass === false);

  console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
  if (failures > 0) process.exitCode = 1;
}

// evaluateBriefing 을 라이브러리로 import 할 때는 CLI 디스패치를 건너뛴다
const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  const args = process.argv.slice(2);
  if (args[0] === '--self-test') {
    selfTest();
  } else {
    const clean = args.includes('--clean');
    const asJson = args.includes('--json');
    const withJudge = args.includes('--judge');
    const dataDir = resolveDataDir(args, SAMPLE_DIR);
    const signalsFileIdx = args.indexOf('--signals-file');
    const signalsFile = signalsFileIdx !== -1 ? args[signalsFileIdx + 1] : undefined;
    // 파일 인자 = 플래그(와 --data-dir / --signals-file 값)를 제외한 첫 위치 인자.
    const dataDirIdx = args.indexOf('--data-dir');
    const file = args.find((a, i) => !a.startsWith('--')
      && (dataDirIdx === -1 || i !== dataDirIdx + 1)
      && (signalsFileIdx === -1 || i !== signalsFileIdx + 1));
    if (!file) {
      console.error('사용법: node test/briefing-eval.mjs [--clean] [--judge] [--json] [--data-dir <dir> | --signals-file <packet.json>] <briefing.md> | --self-test');
      process.exitCode = 2;
    } else {
      const signals = signalsFile
        ? signalsFromPacket(JSON.parse(readFileSync(signalsFile, 'utf8')))
        : signalsForDataDir(dataDir);
      const briefingText = readFileSync(file, 'utf8');
      const result = evaluateBriefing(briefingText, { mode: clean ? 'clean' : 'normal', signals });

      // 2차: LLM Judge — 규칙 채점 PASS 인 양성 브리핑에만 (advisory, PASS/FAIL 불변).
      let judge = null;
      if (withJudge) {
        if (result.pass && !result.negative) {
          const { judgeBriefing } = await import('../common/judge.mjs');
          judge = await judgeBriefing(briefingText, signals);
        } else {
          judge = { skipped: true, reason: result.negative ? '음성 브리핑 — 인과 판정 대상 아님' : '규칙 채점 FAIL — 1차부터 통과할 것' };
        }
      }

      if (asJson) {
        console.log(JSON.stringify({ dataDir: signalsFile ? undefined : dataDir, signalsFile, ...result, judge }, null, 2));
      } else {
        printReport(result);
        if (judge) {
          console.log('\n=== 인과 품질 판정 (LLM Judge · advisory) ===');
          if (judge.skipped) {
            console.log(`skip  ${judge.reason}`);
          } else {
            for (const r of judge.perSignal) {
              console.log(`  ${r.id}  인과 ${r.causality}/2 · 의사결정 ${r.decision}/2 · 반증가능성 ${r.falsifiability}/2 — ${r.comment}`);
            }
            console.log(`\n[종합] ${judge.advisory} (평균: 인과 ${judge.averages.causality} · 의사결정 ${judge.averages.decision} · 반증 ${judge.averages.falsifiability}) — provider: ${judge.provider}`);
            console.log('(advisory — PASS/FAIL 판정은 위 규칙 채점이 확정)');
          }
        }
      }
      if (!result.pass) process.exitCode = 1;
    }
  }
}
