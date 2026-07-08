#!/usr/bin/env node
/**
 * 브리핑 자동 채점 (briefing-eval) — README 의 검증 기준을 실행 가능하게 만든다.
 *
 * 채점 축 3가지:
 *   1. 재현율   — 심어둔 이상 신호 4개를 브리핑이 포착했는가 (신호별 마커 2개 이상)
 *   2. 표현 안전성 — 단정 표현("분식입니다", "확실합니다" 등)을 쓰지 않았는가
 *   3. 구조     — 결론 / 근거 / 확신도 / 권고 조치 섹션을 갖췄는가
 *
 * 사용:
 *   node src/test/briefing-eval.mjs <briefing.md>   # 생성된 브리핑 파일 채점
 *   node src/test/briefing-eval.mjs --self-test     # 채점기 자체 회귀 테스트
 */
import { readFileSync } from 'node:fs';

// 정답지 마커 — 정본은 submission/README.md 의 "데모 데이터에 심어둔 이상 신호" 표.
const SIGNALS = [
  {
    id: 'S1',
    name: '수익 조기 인식 의심',
    markers: [/46[.,]9/, /11[.,]3/, /[-−]\s?310/, /계약부채|선수금/],
  },
  {
    id: 'S2',
    name: '특정 거래처 신용 집중',
    markers: [/91[.,]1/, /1[,.]?750/, /에이프릴리테일/],
  },
  {
    id: 'S3',
    name: '원가 배분 왜곡 (보이지 않는 손실)',
    markers: [/[-−]\s?880/, /기계\s?시간/, /1[,.]?350/, /45\s?%/],
  },
  {
    id: 'S4',
    name: '차입 의존 성장·금리 노출',
    markers: [/이자보상/, /8[.,]9/, /변동금리/, /14[,.]?500/],
  },
];
const MIN_MARKERS_PER_SIGNAL = 2;

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

export function evaluateBriefing(text) {
  const signals = SIGNALS.map((signal) => {
    const hits = signal.markers.filter((m) => m.test(text));
    return {
      id: signal.id,
      name: signal.name,
      hitCount: hits.length,
      detected: hits.length >= MIN_MARKERS_PER_SIGNAL,
    };
  });

  const violations = FORBIDDEN
    .filter(({ pattern }) => pattern.test(text))
    .map(({ label }) => label);

  const missingSections = REQUIRED_SECTIONS
    .filter(({ pattern }) => !pattern.test(text))
    .map(({ label }) => label);

  const recall = signals.filter((s) => s.detected).length;
  return {
    recall,
    total: SIGNALS.length,
    signals,
    violations,
    missingSections,
    pass: recall === SIGNALS.length && violations.length === 0 && missingSections.length === 0,
  };
}

function printReport(result) {
  console.log('=== 브리핑 채점 (briefing-eval) ===');
  console.log(`\n[재현율] ${result.recall}/${result.total}`);
  for (const s of result.signals) {
    console.log(`${s.detected ? '  ok' : 'MISS'}  ${s.id} ${s.name} (마커 ${s.hitCount}개 발견)`);
  }
  console.log(`\n[표현 안전성] ${result.violations.length === 0 ? '위반 없음' : `위반 ${result.violations.length}건`}`);
  for (const v of result.violations) console.log(`FAIL  단정 표현: ${v}`);
  console.log(`\n[구조] ${result.missingSections.length === 0 ? '필수 섹션 모두 존재' : `누락: ${result.missingSections.join(', ')}`}`);
  console.log(result.pass ? '\nEVAL PASS' : '\nEVAL FAIL');
}

function selfTest() {
  const good = `
# CEO Briefing
## 1. 수익 조기 인식 가능성
결론: 매출은 11.3% 증가했지만 매출채권이 46.9% 늘었고 영업현금흐름이 -310으로 전환됐습니다.
근거: 계약부채 340→180 감소.
확신도: 가설 — 기말 전후 전표 확인 필요.
## 2. 거래처 집중
근거: 90일 초과 채권 중 에이프릴리테일 1,750 (91.1%).
## 3. 원가 왜곡
근거: 기계시간 45% 기준 재배부 시 공통원가 1,350 — 영업손익 -880 전환.
## 4. 금리 노출
근거: 변동금리 차입 14,500, 이자보상배율 8.9배로 하락.
권고 조치: 재무팀이 고정금리 전환 조건을 1주 내 확인.
`;
  const bad = `
# 보고
매출채권이 늘었는데 이것은 명백한 분식회계입니다. 확실합니다.
제품C는 -880 적자입니다. 기계시간 45%.
`;
  const goodResult = evaluateBriefing(good);
  const badResult = evaluateBriefing(bad);

  let failures = 0;
  const assert = (name, cond) => {
    console.log(`${cond ? '  ok' : 'FAIL'}  ${name}`);
    if (!cond) failures += 1;
  };

  console.log('=== briefing-eval self-test ===');
  assert('정상 브리핑: 재현율 4/4', goodResult.recall === 4);
  assert('정상 브리핑: 단정 표현 없음', goodResult.violations.length === 0);
  assert('정상 브리핑: 필수 섹션 충족', goodResult.missingSections.length === 0);
  assert('정상 브리핑: 종합 PASS', goodResult.pass === true);
  assert('불량 브리핑: 신호 누락 감지 (S1·S2 미포착)', badResult.recall < 4);
  assert('불량 브리핑: 단정 표현 감지', badResult.violations.length >= 2);
  assert('불량 브리핑: 섹션 누락 감지', badResult.missingSections.length > 0);
  assert('불량 브리핑: 종합 FAIL', badResult.pass === false);

  console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
  if (failures > 0) process.exitCode = 1;
}

const arg = process.argv[2];
if (arg === '--self-test') {
  selfTest();
} else if (arg) {
  const result = evaluateBriefing(readFileSync(arg, 'utf8'));
  printReport(result);
  if (!result.pass) process.exitCode = 1;
} else {
  console.error('사용법: node src/test/briefing-eval.mjs <briefing.md> | --self-test');
  process.exitCode = 2;
}
