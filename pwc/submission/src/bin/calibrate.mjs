#!/usr/bin/env node
/**
 * 임계값 캘리브레이션 (calibrate) — 외부 신호 임계값을 실제 상장사 모집단에 대고 측정한다.
 *
 * "임계값 근거가 무엇인가?"에 대한 답: 건전하다고 간주되는 코스피 비금융 대형주 코호트에서
 * 신호별 발화율을 측정해, 기본 임계값이 과민(발화율 과다)하지도 무디지도 않음을
 * 재현 가능한 수치로 제시한다. 프리셋 튜닝 시에도 같은 명령으로 전후를 비교한다.
 *
 * 사용:
 *   node bin/calibrate.mjs                       # 기본 코호트 15사 (common/presets/calibration-cohort.json)
 *   node bin/calibrate.mjs --top 5               # 코호트 앞에서 N사만 (요금 한도 절약)
 *   node bin/calibrate.mjs --preset semiconductor # 프리셋 적용 후 발화율 비교
 *   node bin/calibrate.mjs --cohort <파일> --json
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { financialFull, disclosures as dartDisclosures } from '../dart/client.mjs';
import { extractFullSeries, deriveExternalSignals } from '../common/dart-signals.mjs';
import { resolveThresholds, PRESETS_DIR } from '../common/presets.mjs';
import { safeErrorMessage } from '../common/env.mjs';

const argv = process.argv.slice(2);
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};
const asJson = argv.includes('--json');
const ymd = (d) => d.toISOString().slice(0, 10).replaceAll('-', '');

const cohortPath = flag('--cohort') ?? join(PRESETS_DIR, 'calibration-cohort.json');
const cohort = JSON.parse(readFileSync(cohortPath, 'utf8'));
const top = Number(flag('--top') ?? cohort.companies.length);
const companies = cohort.companies.slice(0, Math.max(1, top));
const days = Math.max(1, Math.min(Number(flag('--days') ?? 90), 365));
const fsDiv = flag('--fs-div') ?? 'CFS';
const { thresholds, presetUsed } = resolveThresholds({ preset: flag('--preset'), kind: 'external' });

const nowYear = new Date().getFullYear();
const results = [];
const failures = [];

for (const corp of companies) {
  try {
    let year = null;
    let body = null;
    for (const y of [nowYear - 1, nowYear - 2]) {
      const b = await financialFull({ corpCode: corp.corpCode, year: y, reprtCode: '11011', fsDiv });
      if (b.status !== '013' && b.list?.length) { year = y; body = b; break; }
    }
    if (!body) throw new Error('사업보고서 재무제표 없음');
    const disc = await dartDisclosures({
      corpCode: corp.corpCode,
      bgnDe: ymd(new Date(Date.now() - days * 86_400_000)), endDe: ymd(new Date()), pageCount: 100,
    });
    const signals = deriveExternalSignals({ series: extractFullSeries(body), disclosures: disc.list ?? [], days }, thresholds);
    results.push({
      name: corp.name, corpCode: corp.corpCode, year,
      present: signals.filter((s) => s.present).map((s) => s.id),
      notEvaluable: signals.filter((s) => !s.evaluable).map((s) => s.id),
    });
  } catch (e) {
    failures.push({ name: corp.name, corpCode: corp.corpCode, error: safeErrorMessage(e) });
  }
}

const SIGNAL_IDS = ['E1', 'E2', 'E3', 'E4', 'E5'];
const rates = SIGNAL_IDS.map((id) => {
  const evaluable = results.filter((r) => !r.notEvaluable.includes(id));
  const fired = evaluable.filter((r) => r.present.includes(id));
  return {
    id,
    evaluable: evaluable.length,
    fired: fired.length,
    ratePct: evaluable.length ? Math.round((fired.length / evaluable.length) * 1000) / 10 : null,
    companies: fired.map((r) => r.name),
  };
});

if (asJson) {
  console.log(JSON.stringify({ cohort: cohortPath, sampled: results.length, days, fsDiv, presetUsed, thresholds, rates, results, failures }, null, 2));
} else {
  console.log(`=== 임계값 캘리브레이션 (calibrate) — 코호트 ${results.length}/${companies.length}사 · ${fsDiv} · 공시창 ${days}일${presetUsed ? ` · 프리셋 ${presetUsed}` : ''} ===`);
  for (const r of rates) {
    console.log(`${r.id}  발화 ${r.fired}/${r.evaluable} (${r.ratePct ?? 'n/a'}%)${r.companies.length ? ` — ${r.companies.join(', ')}` : ''}`);
  }
  console.log('\n[기업별]');
  for (const r of results) {
    console.log(`  ${r.name} (${r.year}): ${r.present.length ? r.present.join(', ') : '—'}${r.notEvaluable.length ? ` (판정불가: ${r.notEvaluable.join(', ')})` : ''}`);
  }
  for (const f of failures) console.log(`  FAIL ${f.name}: ${f.error}`);
  console.log('\n해석 기준: 건전 코호트에서 재무 신호(E1~E4) 발화율이 0~15% 대역이면 임계값이 과민하지 않다는 근거.');
  console.log('E5(공시 행간)는 확인 신호라 발화율이 높은 것이 정상 — 리스크 단정이 아니라 점검 지시로 서술된다.');
}
if (failures.length === companies.length) process.exitCode = 1;
