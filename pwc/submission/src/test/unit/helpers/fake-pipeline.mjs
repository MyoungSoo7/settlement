/**
 * quarterly-briefing-batch 테스트용 가짜 파이프라인 — 네트워크·LLM 없이
 * --out-dir 에 산출물을 쓰고, FAKE_PIPELINE_FAIL_FOR 에 지정된 기업이면 EVAL FAIL(exit 1)을 흉내낸다.
 * FAKE_PIPELINE_NO_PNG 가 있으면 스냅샷 PNG 를 생략한다 (python 부재 경로 재현).
 *
 * 승격(--escalate-signals) 테스트 훅:
 *   FAKE_PIPELINE_CALLS          — 호출 argv 를 JSONL 로 append (1차/승격 인자 검증용)
 *   FAKE_PIPELINE_SIGNALS        — present 로 만들 신호 id CSV (예: "E1,E4") → diagnostic-packet.json
 *   FAKE_PIPELINE_FAIL_ESCALATED — out-dir 이 escalated 폴더면 EVAL FAIL(exit 1) — 승격 폴백 경로 재현
 * docx 내용에 out-dir 폴더명을 넣어(escalated 여부) 어느 단의 산출물이 스탬프됐는지 검증 가능하게 한다.
 */
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';

const argv = process.argv.slice(2);
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};

const company = flag('--company');
const outDir = flag('--out-dir');
if (!company || !outDir) {
  console.error('--company / --out-dir 필요');
  process.exit(2);
}
if (process.env.FAKE_PIPELINE_CALLS) {
  appendFileSync(process.env.FAKE_PIPELINE_CALLS, `${JSON.stringify(argv)}\n`, 'utf8');
}
mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, 'briefing.md'), `# 가짜 브리핑 — ${company}`, 'utf8');

// 진단 패킷 — 실 파이프라인처럼 신호 배열(id·present)을 남긴다. 승격 판정이 읽는 유일한 계약.
const presentIds = new Set(
  (process.env.FAKE_PIPELINE_SIGNALS ?? '').split(',').map((s) => s.trim()).filter(Boolean),
);
writeFileSync(join(outDir, 'diagnostic-packet.json'), JSON.stringify({
  signals: ['E1', 'E2', 'E3', 'E4', 'E5', 'E8'].map((id) => ({ id, present: presentIds.has(id) })),
}), 'utf8');

if ((process.env.FAKE_PIPELINE_FAIL_FOR ?? '') === company) {
  console.error('EVAL FAIL (가짜)');
  process.exit(1);
}
if (process.env.FAKE_PIPELINE_FAIL_ESCALATED && basename(outDir) === 'escalated') {
  console.error('EVAL FAIL (가짜 — 승격 재실행)');
  process.exit(1);
}
writeFileSync(join(outDir, 'briefing.docx'), `PK-fake-docx-${company}-${basename(outDir)}`, 'utf8');
if (!process.env.FAKE_PIPELINE_NO_PNG) {
  writeFileSync(join(outDir, 'executive-summary.png'), `fake-png-${company}`, 'utf8');
}
console.log('EVAL PASS (가짜)');
