#!/usr/bin/env node
/**
 * LLM Judge 라이브 스모크 — 키가 있으면 실제 LLM 으로 판정 회귀를 확인한다.
 *
 * 검증 시나리오: "잘 쓴 인과 브리핑"과 "패킷 수치 복붙 브리핑"을 같은 신호로 판정해
 * 전자의 causality 평균이 후자보다 높게 나오는지 본다 (Judge 가 인과와 나열을 구분하는가).
 * 키가 없으면 스킵 (다른 스모크와 동일 규약).
 *
 * 사용: node src/test/judge-smoke.mjs
 */
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { judgeBriefing, resolveProvider } from '../common/judge.mjs';
import { signalsForDataDir } from './briefing-eval.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SAMPLE_DIR = join(HERE, '..', 'data', 'sample');

const provider = resolveProvider();
if (!provider) {
  console.log('SKIP — GEMINI_API_KEY/ANTHROPIC_API_KEY 없음 (judge 는 키 없이는 비활성이 정상 동작)');
  process.exit(0);
}

const signals = signalsForDataDir(SAMPLE_DIR);

// 인과 사슬이 있는 브리핑 (원인→결과→경영 함의 + 구체 판별 테스트).
const CAUSAL = `# CEO Briefing
## 1. 수익 조기 인식 가능성
결론: 매출 11.3% 성장의 상당분이 현금 없는 성장입니다.
근거: 매출채권 +46.9%, 계약부채 340→180, 영업현금흐름 880→-310.
왜 문제인가: 매출 기록 속도가 현금 회수 속도를 크게 앞서고 선수금까지 줄었다는 것은, 아직 이행이 끝나지 않았거나 회수가 불확실한 매출이 앞당겨 기록되었을 가능성을 뜻합니다. 이것이 사실이면 다음 분기 실적 절벽과 운전자본 압박이 함께 오고, 그 부족분을 차입으로 메우면 이자 부담이 이익을 잠식하는 악순환으로 이어집니다.
확신도: 가설 — 확인 필요.
판별 테스트: 기말 ±7일 매출 전표 분포를 뽑아 월중 평균과 대조하고, 상위 10건의 검수 완료 증빙을 확인 (재무팀, 1주). 전표가 기말에 몰려 있지 않다면 이 가설은 기각됩니다.
## 2. 특정 거래처 신용 집중
결론: 장기 미수의 91.1%가 에이프릴리테일 한 곳 — 회수 캠페인이 아니라 여신 결정 사안입니다.
근거: 90일 초과 1,920 중 에이프릴리테일 1,750.
왜 문제인가: 미수가 한 곳에 몰려 있으면 그 거래처의 지급 능력 문제이며, 회수 불능 확정 시 분기 영업이익의 절반 이상이 사라져 배당·투자 계획을 수정해야 합니다.
확신도: 가설. 판별 테스트: 해당사 여신계약·담보등기 대조, 담보가 채권을 못 덮으면 신규 출고 중단 (영업·재무팀, 3일).
## 3. 원가 배분 왜곡 — 제품C
결론: 제품C 흑자 +20은 배부 기준의 착시로, 기계시간 45% 기준 재배부 시 -880 적자입니다 (재배부 공통원가 1,350).
왜 문제인가: 설비를 많이 쓰는 제품이 원가를 덜 부담하면 다른 제품 이익이 제품C 손실을 메워주는 구조가 되어, 팔수록 전사 이익이 새는데도 사업부 성과표에는 흑자로 보입니다. 가격·생산·철수 결정이 왜곡된 손익 위에서 내려지고 있습니다.
확신도: 확인됨(산술). 판별 테스트: 기계시간 로그 원천으로 배부율 재산정, ABC 시범 적용 (원가팀, 2주).
## 4. 차입 의존 성장·금리 노출
결론: 영업현금 악화를 변동금리 차입(14,500)으로 메워 이자보상배율이 23.9→8.9배로 떨어졌습니다.
왜 문제인가: 리스크 1의 현금 부족 → 차입 증가 → 이자 부담 증가 → 현금 부족 심화의 순환이 형성되고 있고, 전액 변동금리라면 기준금리 인상 시 잠식 속도가 가속됩니다.
확신도: 가설. 판별 테스트: 차입 약정서 금리 조건·헤지(IRS) 계약 대조, 고정금리 전환 견적 (재무팀, 1주).
## 확인 범위와 한계
결론·근거·확신도·권고 조치: 위 4건을 각 팀 배정, 1주 내 회신.
`;

// 패킷 수치를 나열만 한 브리핑 (구조·마커는 규칙 채점을 통과할 수 있는 수준).
const COPY = `# CEO Briefing
## 1. 수익 조기 인식 가능성
결론: 매출 11.3%, 매출채권 46.9%, 계약부채 340→180, 영업현금흐름 -310입니다.
왜 문제인가: 수치가 위와 같습니다.
확신도: 가설 — 확인 필요. 판별 테스트: 전표 대조.
## 2. 특정 거래처 신용 집중
결론: 에이프릴리테일 1,750, 91.1%입니다.
왜 문제인가: 위 수치와 같습니다.
확신도: 가설. 판별 테스트: 여신 대조.
## 3. 원가 배분 왜곡
결론: 기계시간 45%, 재배부 1,350, 손익 -880입니다.
왜 문제인가: 수치 참조.
확신도: 확인됨. 판별 테스트: 재산정.
## 4. 차입 의존 성장
결론: 변동금리 차입 14,500, 이자보상 8.9배입니다.
왜 문제인가: 수치 참조.
확신도: 가설. 판별 테스트: 약정서 대조.
## 확인 범위
결론·근거·권고 조치: 각 팀 확인.
`;

console.log(`=== judge live smoke (provider: ${provider}) ===`);
const causal = await judgeBriefing(CAUSAL, signals);
const copy = await judgeBriefing(COPY, signals);

const show = (label, r) => {
  if (r.skipped) { console.log(`${label}: SKIP — ${r.reason}`); return null; }
  console.log(`${label}: 인과 ${r.averages.causality} · 의사결정 ${r.averages.decision} · 반증 ${r.averages.falsifiability} → ${r.advisory}`);
  for (const p of r.perSignal) console.log(`   ${p.id} [${p.causality}/${p.decision}/${p.falsifiability}] ${p.comment}`);
  return r;
};
const a = show('인과 브리핑', causal);
const b = show('복붙 브리핑', copy);

if (a && b) {
  const discriminates = a.averages.causality > b.averages.causality;
  console.log(discriminates
    ? `\nOK — Judge 가 인과(${a.averages.causality})와 나열(${b.averages.causality})을 구분함`
    : `\nWARN — 구분 실패 (인과 ${a.averages.causality} vs 나열 ${b.averages.causality}) — 프롬프트 앵커 보강 검토`);
  if (!discriminates) process.exitCode = 1;
}
