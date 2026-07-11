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
if (outPath && existsSync(resolve(outPath))) {
  console.error(`--out 경로가 이미 존재합니다: ${outPath} — 기존 리포트는 덮어쓰지 않습니다. 다른 이름을 지정하세요.`);
  process.exit(1);
}
outPath = outPath ?? defaultOutPath();
const prompt = args.join(' ').trim() || DEFAULT_PROMPT;

// 스킬 세션에 저장 경로를 명시 지시 — 세션이 스스로 경로를 정하다 기존 리포트를
// 덮어쓴 실측 사고 방어 (스킬 SKILL.md 의 "저장 경로 규칙 (강제)" 1번과 짝).
const savePathForSession = outPath.replaceAll('\\', '/');
const promptWithHarness = `${prompt}

[하네스 지시] 최종 리포트 파일은 정확히 \`${savePathForSession}\` 에만 저장하라. 다른 outputs/result-*.md 파일을 새로 만들거나 수정·덮어쓰기하지 마라.`;

console.log(`프롬프트: ${prompt}`);
console.log(`세션 저장 경로 지시: ${savePathForSession}`);
console.log('codex 실행 중... (도구 수십 회 호출 — 몇 분 걸릴 수 있습니다)\n');

// 프롬프트는 stdin('-')으로 전달 — Windows 셸 심(shim) 경유 시 공백·한글 인자가
// 재분리되는 문제("unexpected argument" exit 2)를 원천 차단한다.
// -s workspace-write: 비대화 세션의 샌드박스가 read-only 로 뜨는 경우가 있어(실측)
// 스킬의 in-session 저장(outputs/, cycle 상태 파일)이 되도록 명시한다.
const run = spawnSync('codex', ['exec', '--skip-git-repo-check', '-s', 'workspace-write', '-'], {
  encoding: 'utf8',
  input: promptWithHarness,
  shell: process.platform === 'win32', // Windows 의 codex 셸 심 대응
  timeout: 10 * 60 * 1000,
});

if (run.error || run.status !== 0 || !run.stdout?.trim()) {
  console.error('codex 실행 실패:', run.error?.message ?? `exit ${run.status}`);
  if (run.stderr) console.error(String(run.stderr).slice(-500));
  process.exit(1);
}

const absolute = resolve(outPath);
console.log(run.stdout.trim());

if (existsSync(absolute)) {
  // 세션(스킬)이 하네스 지시 경로에 풀 리포트를 저장함 — 콘솔 요약으로 덮어쓰지 않는다
  console.log(`\n>>> 저장됨 (세션이 직접 저장): ${absolute}`);
} else {
  // 세션이 파일 저장을 생략한 환경 폴백 — 최종 응답을 래퍼가 대신 저장
  const stamp = new Date().toISOString().replace('T', ' ').slice(0, 16);
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
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, report, 'utf8');
  console.log(`\n>>> 저장됨 (래퍼 폴백): ${absolute}`);
}
