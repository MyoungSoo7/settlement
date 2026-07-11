/**
 * demo-e2e 테스트용 가짜 에이전트 — stdin 프롬프트를 무시하고
 * FAKE_BRIEFING_FILE 의 내용을 브리핑으로 출력한다 (LLM 없는 결정론 E2E).
 */
import { readFileSync } from 'node:fs';

const file = process.env.FAKE_BRIEFING_FILE;
if (!file) {
  console.error('FAKE_BRIEFING_FILE env 필요');
  process.exit(1);
}
process.stdout.write(readFileSync(file, 'utf8'));
