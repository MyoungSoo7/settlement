/**
 * quarterly-briefing-batch 테스트용 가짜 파이프라인 — 네트워크·LLM 없이
 * --out-dir 에 산출물을 쓰고, FAKE_PIPELINE_FAIL_FOR 에 지정된 기업이면 EVAL FAIL(exit 1)을 흉내낸다.
 * FAKE_PIPELINE_NO_PNG 가 있으면 스냅샷 PNG 를 생략한다 (python 부재 경로 재현).
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

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
mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, 'briefing.md'), `# 가짜 브리핑 — ${company}`, 'utf8');

if ((process.env.FAKE_PIPELINE_FAIL_FOR ?? '') === company) {
  console.error('EVAL FAIL (가짜)');
  process.exit(1);
}
writeFileSync(join(outDir, 'briefing.docx'), `PK-fake-docx-${company}`, 'utf8');
if (!process.env.FAKE_PIPELINE_NO_PNG) {
  writeFileSync(join(outDir, 'executive-summary.png'), `fake-png-${company}`, 'utf8');
}
console.log('EVAL PASS (가짜)');
