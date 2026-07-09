#!/usr/bin/env node
/**
 * 원커맨드 E2E 데모 (demo-e2e) — 게이트 → 신호 파생 → (에이전트 CLI) 브리핑 생성 → 자동 채점을
 * 명령 하나로 잇는다. 심사역/CEO 가 "재현율 4/4 EVAL PASS" 까지를 눈앞에서 본다.
 *
 *   1. verify-books   불변식 게이트 (FAIL 이면 여기서 중단 — 추론 진입 금지)
 *   2. detect-signals 신호 파생 (진단 패킷 JSON 확보)
 *   3. 에이전트 CLI   claude -p / codex exec 자동 감지 — 패킷을 프롬프트로 주고 브리핑 생성
 *   4. briefing-eval  규칙 채점 (+ --judge 시 LLM 인과 판정)
 *
 * 에이전트 CLI 가 없으면(또는 --agent none) 프롬프트 파일을 저장하고 수동 절차를 안내한다 —
 * LLM 없이도 데모가 막히지 않는다.
 *
 * 사용:
 *   node bin/demo-e2e.mjs                                   # 동봉 샘플, 에이전트 자동 감지
 *   node bin/demo-e2e.mjs --data-dir <폴더> --judge
 *   node bin/demo-e2e.mjs --agent none                      # 수동 안내 모드
 *   node bin/demo-e2e.mjs --agent "node my-agent.mjs"       # 커스텀 (stdin 으로 프롬프트 수신)
 *   node bin/demo-e2e.mjs --out <폴더>                      # 산출물(briefing.md 등) 위치 지정
 */
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};
const DATA_DIR = flag('--data-dir') ?? join(HERE, '..', 'data', 'sample');
const OUT_DIR = resolve(flag('--out') ?? join(tmpdir(), `tca-demo-${process.pid}`));
const withJudge = argv.includes('--judge');
mkdirSync(OUT_DIR, { recursive: true });

const runCli = (script, args, opts = {}) =>
  spawnSync(process.execPath, [join(HERE, script), ...args], { encoding: 'utf8', ...opts });

const step = (n, title) => console.log(`\n━━ [${n}] ${title} ━━`);

// ── 1. 불변식 게이트 ─────────────────────────────────────────
step(1, '불변식 게이트 (verify-books)');
const gate = runCli('verify-books.mjs', ['--data-dir', DATA_DIR]);
process.stdout.write(gate.stdout);
if (gate.status !== 0) {
  console.error('\nDEMO 중단 — 게이트 FAIL 상태에서는 추론(브리핑 생성)에 진입하지 않습니다.');
  process.exit(1);
}

// ── 2. 신호 파생 ─────────────────────────────────────────────
step(2, '신호 파생 (detect-signals)');
const human = runCli('detect-signals.mjs', ['--data-dir', DATA_DIR]);
process.stdout.write(human.stdout);
const packetRun = runCli('detect-signals.mjs', ['--data-dir', DATA_DIR, '--json']);
if (packetRun.status !== 0) {
  console.error('신호 파생 실패');
  process.exit(1);
}
const packetPath = join(OUT_DIR, 'packet.json');
writeFileSync(packetPath, packetRun.stdout);

// ── 3. 에이전트 브리핑 생성 ──────────────────────────────────
const prompt = `너는 trusted-ceo-agent 플러그인의 ceo-risk-recon 오케스트레이터다.
아래 진단 패킷은 불변식 게이트(GATE PASS)를 통과한 데이터에서 결정론으로 파생된 신호다.
패킷의 수치를 그대로 인용해(암산 금지) CEO 서명용 브리핑을 작성하라.

규칙:
- PRESENT 신호만 리스크로 다룬다. absent 신호를 리스크로 승격하면 오탐으로 채점된다.
- 리스크당: 결론 / 근거(수치+출처) / 왜 문제인가(인과 사슬 — 수치 나열 금지) / 확신도(확인됨|가설) / 판별 테스트 / 권고 조치.
- 마지막에 "확인 범위와 한계" 절. 단정 표현(분식입니다·확실합니다·명백한) 금지.
- 다른 설명 없이 마크다운 브리핑 본문만 출력하라.

[진단 패킷]
${packetRun.stdout}`;
const promptPath = join(OUT_DIR, 'prompt.txt');
writeFileSync(promptPath, prompt);

step(3, '에이전트 브리핑 생성');
const agentFlag = flag('--agent');
let agentCmd = null;
if (agentFlag && agentFlag !== 'none') {
  agentCmd = agentFlag.split(/\s+/);
} else if (agentFlag !== 'none') {
  // 자동 감지: claude → codex 순
  for (const [probe, cmd] of [['claude', ['claude', '-p']], ['codex', ['codex', 'exec']]]) {
    const check = spawnSync(probe, ['--version'], { encoding: 'utf8', shell: process.platform === 'win32' });
    if (check.status === 0) { agentCmd = cmd; break; }
  }
}

if (!agentCmd) {
  console.log('에이전트 CLI 미감지(claude/codex) 또는 --agent none — 수동 절차로 전환합니다.');
  console.log(`  1) 프롬프트 파일을 에이전트에 전달: ${promptPath}`);
  console.log(`  2) 생성된 브리핑을 저장 후 채점: node "${join(HERE, '..', 'test', 'briefing-eval.mjs')}" --data-dir "${DATA_DIR}"${withJudge ? ' --judge' : ''} <briefing.md>`);
  process.exit(0);
}

console.log(`에이전트: ${agentCmd.join(' ')} (프롬프트는 stdin 으로 전달)`);
const agent = spawnSync(agentCmd[0], agentCmd.slice(1), {
  input: prompt, encoding: 'utf8', shell: process.platform === 'win32',
  maxBuffer: 10 * 1024 * 1024, timeout: 600_000,
});
if (agent.status !== 0 || !agent.stdout?.trim()) {
  console.error(`에이전트 실행 실패 (exit ${agent.status}): ${(agent.stderr ?? '').slice(0, 400)}`);
  console.error(`수동 절차: 프롬프트 파일 ${promptPath} 를 에이전트에 직접 전달하세요.`);
  process.exit(1);
}
const briefingPath = join(OUT_DIR, 'briefing.md');
writeFileSync(briefingPath, agent.stdout);
console.log(`브리핑 저장: ${briefingPath} (${agent.stdout.length}자)`);

// ── 4. 자동 채점 ─────────────────────────────────────────────
step(4, `자동 채점 (briefing-eval${withJudge ? ' + LLM Judge' : ''})`);
const evalArgs = ['--data-dir', DATA_DIR, ...(withJudge ? ['--judge'] : []), briefingPath];
const evaluation = spawnSync(process.execPath, [join(HERE, '..', 'test', 'briefing-eval.mjs'), ...evalArgs], { encoding: 'utf8' });
process.stdout.write(evaluation.stdout);
if (evaluation.stderr) process.stderr.write(evaluation.stderr);

console.log(`\n산출물: ${OUT_DIR} (packet.json / prompt.txt / briefing.md)`);
process.exit(evaluation.status ?? 1);
