#!/usr/bin/env node
/**
 * 원샷 추천 리포트 — codex 를 비대화로 실행해 결과를 outputs/result-<날짜>.md 로 저장한다.
 * 같은 날 재실행하면 -2, -3 순번이 붙어 이전 리포트를 덮어쓰지 않는다.
 *
 * 실행: node src/bin/recommend.mjs                          # 기본 프롬프트
 *       node src/bin/recommend.mjs "이번 분기 300만원 3종목"  # 프롬프트 지정
 *       node src/bin/recommend.mjs --out my-result.md "..."  # 저장 경로 직접 지정
 *
 * 전제: codex CLI 로그인 + 플러그인 설치(src/bin/install-codex.ps1). 몇 분 걸릴 수 있다.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const DEFAULT_PROMPT = '주식 투자 추천해줘. 초보자 기준으로 근거와 함께.';

function defaultOutPath() {
  const today = new Date().toLocaleDateString('sv-SE'); // 로컬 기준 YYYY-MM-DD
  const base = `outputs/result-${today}`;
  if (!existsSync(`${base}.md`)) return `${base}.md`;
  for (let n = 2; ; n += 1) {
    if (!existsSync(`${base}-${n}.md`)) return `${base}-${n}.md`;
  }
}

const args = process.argv.slice(2);
let outPath = null;
const outIdx = args.indexOf('--out');
if (outIdx >= 0) {
  outPath = args[outIdx + 1] ?? null;
  args.splice(outIdx, 2);
}
outPath = outPath ?? defaultOutPath();
const prompt = args.join(' ').trim() || DEFAULT_PROMPT;

console.log(`프롬프트: ${prompt}`);
console.log('codex 실행 중... (도구 수십 회 호출 — 몇 분 걸릴 수 있습니다)\n');

// 프롬프트는 stdin('-')으로 전달 — Windows 셸 심(shim) 경유 시 공백·한글 인자가
// 재분리되는 문제("unexpected argument" exit 2)를 원천 차단한다.
const run = spawnSync('codex', ['exec', '--skip-git-repo-check', '-'], {
  encoding: 'utf8',
  input: prompt,
  shell: process.platform === 'win32', // Windows 의 codex 셸 심 대응
  timeout: 10 * 60 * 1000,
});

if (run.error || run.status !== 0 || !run.stdout?.trim()) {
  console.error('codex 실행 실패:', run.error?.message ?? `exit ${run.status}`);
  if (run.stderr) console.error(String(run.stderr).slice(-500));
  process.exit(1);
}

const now = new Date();
const stamp = now.toISOString().replace('T', ' ').slice(0, 16);
const report = [
  '# 주식 투자 리포트 (kakaopay-invest-companion)',
  '',
  `> 생성: ${stamp} (UTC) · 프롬프트: "${prompt}"`,
  '> 본 리포트는 규칙 기반 스크리닝 산출물이며 투자자문·투자권유가 아닙니다.',
  '',
  '---',
  '',
  run.stdout.trim(),
  '',
].join('\n');

const absolute = resolve(outPath);
mkdirSync(dirname(absolute), { recursive: true });
writeFileSync(absolute, report, 'utf8');
console.log(run.stdout.trim());
console.log(`\n>>> 저장됨: ${absolute}`);
