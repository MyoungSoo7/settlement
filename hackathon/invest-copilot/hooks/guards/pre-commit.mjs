#!/usr/bin/env node
/**
 * git pre-commit 폴백 가드 — 훅 개념이 없는 에이전트(Codex CLI 등)·수동 커밋용.
 * staged diff 의 파일별 최종 내용을 rules.mjs 로 검사한다 (문서/리포트류만).
 */
import { execSync } from 'node:child_process';
import { checkFileContent, formatViolations, isAdviceScope } from './rules.mjs';

const files = execSync('git diff --cached --name-only --diff-filter=ACM', { encoding: 'utf8' })
  .split('\n').map(s => s.trim()).filter(Boolean)
  .filter(isAdviceScope);

let blocked = false;
for (const f of files) {
  let content;
  try { content = execSync(`git show :"${f}"`, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }); }
  catch { continue; }
  const violations = checkFileContent(f, content);
  if (violations.length) {
    console.error(`\n── ${f}\n${formatViolations(violations)}`);
    if (violations.some(v => v.severity === 'BLOCK')) blocked = true;
  }
}

if (blocked) {
  console.error('\ninvest-copilot: BLOCK 위반(보장/단정 표현)이 있어 커밋을 중단합니다.');
  process.exit(1);
}
process.exit(0);
