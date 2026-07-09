#!/usr/bin/env node
/**
 * CEO consulting pipeline runner — 고객 접수부터 서명용 브리핑 채점까지 한 명령.
 *
 *   1. company_identity_gate  국세청 사업자등록 상태/진위 확인 (FAIL 이면 분석 진입 금지)
 *   2. diagnose-company       DART/ECOS(+내부 CSV) 진단 패킷 생성
 *   3. 에이전트 CLI            claude -p / codex exec 자동 감지 — 패킷으로 브리핑 생성
 *   4. briefing-eval          규칙 채점 (+ --judge 시 LLM 인과 판정)
 *
 * 에이전트 CLI 가 없으면(또는 --agent none) 프롬프트 파일과 다음 단계 안내
 * (spreadsheets → compliance → documents)를 남기고 정상 종료한다 — LLM 없이도 막히지 않는다.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { companyIdentityGate } from '../registry/client.mjs';
import { safeErrorMessage } from '../common/env.mjs';
import { briefingToDocx } from '../common/docx.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const argv = process.argv.slice(2);

const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};

const has = (name) => argv.includes(name);

function usage() {
  return `CEO consulting pipeline — 식별 게이트 → 진단 → 브리핑 생성 → 자동 채점

Usage:
  node src/bin/ceo-consulting-pipeline.mjs \\
    --company <기업명> \\
    --business-number <사업자등록번호> \\
    [--representative-name <대표자명>] [--opening-date <YYYYMMDD>] \\
    [--data-dir <표준 CSV 폴더>] [--out-dir <산출물 폴더>] \\
    [--agent none|"<cmd>"] [--judge] \\
    [--corp-code <8자리>] [--year <YYYY>] [--dart-unit-scale <N>] \\
    [--preset <name>] [--docs-dir <문서폴더>]

Pipeline:
  identity gate -> diagnose-company -> agent briefing -> briefing-eval
  (spreadsheets / compliance / documents 단계는 pipeline-next-steps.md 로 안내)

Outputs:
  identity.json / diagnostic-packet.json / pipeline-next-steps.md
  briefing.md + briefing.docx (에이전트 감지 시) / prompt.txt
`;
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

function slug(value) {
  return String(value ?? 'company')
    .trim()
    .toLowerCase()
    .replace(/[^0-9a-z가-힣]+/gi, '-')
    .replace(/^-+|-+$/g, '') || 'company';
}

const step = (n, title) => console.log(`\n━━ [${n}] ${title} ━━`);

function runNode(args) {
  const result = spawnSync(process.execPath, args, {
    cwd: ROOT,
    encoding: 'utf8',
    env: process.env,
    maxBuffer: 10 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${args.join(' ')} 실패\n${result.stderr || result.stdout}`);
  }
  return result.stdout;
}

function renderNextSteps({ companyName, businessNumber, dataDir, outDir, identityPath, packetPath, briefingDone }) {
  const dataLine = dataDir
    ? `내부 표준 CSV 폴더: \`${dataDir}\``
    : '내부 표준 CSV 폴더: 미제공. DART/ECOS/뉴스 기반 외부 진단 모드로 진행.';
  const briefingLine = briefingDone
    ? `이미 생성·채점됨: \`${join(outDir, 'briefing.md')}\` — 아래 프롬프트는 재생성용.`
    : `에이전트 CLI 미사용 — 아래 프롬프트(\`prompt.txt\`)를 에이전트에 전달해 생성한다.`;

  return `# CEO Consulting Pipeline Next Steps

## Pipeline

\`\`\`text
spreadsheets
  -> trusted-ceo-agent (identity gate -> diagnose -> briefing -> eval)
  -> compliance-review / compliance-language
  -> documents
\`\`\`

## Confirmed Inputs

- 기업명: \`${companyName}\`
- 사업자등록번호: \`${businessNumber}\`
- ${dataLine}
- 식별 게이트 결과: \`${identityPath}\`
- 진단 패킷: \`${packetPath}\`

## 1. Spreadsheets

고객 원본 Excel/CSV가 있으면 먼저 \`spreadsheets\` 플러그인으로 다음 표준 파일을 만든다.

\`\`\`text
trial_balance.csv
ar_aging.csv
cost_allocation.csv
\`\`\`

이미 \`--data-dir\` 로 표준 CSV 폴더를 넘겼다면 이 단계는 완료된 것으로 본다.

## 2. Trusted CEO Agent — 브리핑

${briefingLine}

\`\`\`text
기업명 ${companyName}, 사업자등록번호 ${businessNumber} 기준으로 identity.json과 diagnostic-packet.json을 근거로 CEO 브리핑을 작성해줘.
내부 CSV가 있으면 정합성 게이트와 신호 파생 결과를 우선하고, DART/ECOS/네이버 뉴스 신호를 보조 근거로 결합해줘.
각 리스크는 결론, 근거 수치, 확신도, 추가 확인 절차, 권고 조치 순서로 작성해줘.
검토용 산출물은 ${join(outDir, 'briefing.md')} 로 저장해줘.
\`\`\`

## 3. Compliance Review / Language

브리핑 확정 전 아래를 검수한다.

- PII: 대표자명, 계좌번호, 주민번호, 카드번호, 실명+연락처 조합이 불필요하게 노출되지 않는가
- Auditability: 어떤 수치가 어떤 파일/API/시점에서 왔는지 추적 가능한가
- Language: 투자 수익 보장, 원금 보장, 확실한 상승/하락, 매수/매도 지시형 표현이 없는가
- Boundary: CEO 경영 판단 보조 자료이며 투자자문/투자권유가 아님을 명시했는가

## 4. Documents

Word 보고서는 파이프라인이 내장 렌더러로 자동 생성한다 (표지·핵심 리스크 요약표·확신도
배지·면책 푸터 포함, UTF-8/한글 폰트 보장):

\`\`\`text
${join(outDir, 'briefing.docx')}   ← 이미 생성됨 (브리핑 수정 후 재생성: node src/bin/render-briefing-docx.mjs ${join(outDir, 'briefing.md')})
\`\`\`

회사 브랜드 템플릿(로고·표지 규정)이 필요한 경우에만 \`documents\` 플러그인으로 변환한다.

## Verification

\`\`\`powershell
node src/test/briefing-eval.mjs --signals-file "${packetPath}" "${join(outDir, 'briefing.md')}"
\`\`\`
`;
}

if (has('--help') || has('-h')) {
  console.log(usage());
  process.exit(0);
}

const companyName = flag('--company');
const businessNumber = flag('--business-number');
if (!companyName || !businessNumber) {
  fail(usage());
}

const representativeName = flag('--representative-name');
const openingDate = flag('--opening-date');
const dataDir = flag('--data-dir') ? resolve(ROOT, flag('--data-dir')) : '';
const outDir = resolve(ROOT, flag('--out-dir') ?? join('outputs', `${slug(companyName)}-ceo-pipeline`));
const withJudge = has('--judge');
mkdirSync(outDir, { recursive: true });

// ── 1. 식별 게이트 ───────────────────────────────────────────
step(1, '기업 식별 게이트 (company_identity_gate)');
let identity;
try {
  identity = await companyIdentityGate({
    companyName,
    businessNumber,
    representativeName,
    openingDate,
  });
} catch (error) {
  fail(`company_identity_gate 실패: ${safeErrorMessage(error)}`);
}

const identityPath = join(outDir, 'identity.json');
writeFileSync(identityPath, JSON.stringify(identity, null, 2), 'utf8');
for (const w of identity.warnings ?? []) console.log(`  주의: ${w}`);

if (!identity.analysisAllowed) {
  const nextStepsPath = join(outDir, 'pipeline-next-steps.md');
  writeFileSync(nextStepsPath, renderNextSteps({
    companyName,
    businessNumber,
    dataDir,
    outDir,
    identityPath,
    packetPath: '(identity gate failed)',
    briefingDone: false,
  }), 'utf8');
  fail(`식별 게이트 실패 — 후속 분석을 중단합니다. 결과: ${identityPath}`);
}
console.log(`  식별 확인 — 분석 진행 허용 (${identityPath})`);

// ── 2. 진단 패킷 생성 ────────────────────────────────────────
step(2, '진단 패킷 생성 (diagnose-company)');
const diagnoseArgs = ['src/bin/diagnose-company.mjs', '--json'];
const corpCode = flag('--corp-code');
if (corpCode) diagnoseArgs.push('--corp-code', corpCode);
else diagnoseArgs.push('--company', companyName);
for (const name of ['--year', '--dart-unit-scale', '--preset', '--docs-dir', '--news-query', '--news-display']) {
  if (flag(name) !== undefined) diagnoseArgs.push(name, flag(name));
}
if (has('--with-news')) diagnoseArgs.push('--with-news');
if (dataDir) diagnoseArgs.push('--data-dir', dataDir);

let packetText;
try {
  packetText = runNode(diagnoseArgs);
} catch (error) {
  fail(`diagnose-company 실패: ${safeErrorMessage(error)}`);
}

const packetPath = join(outDir, 'diagnostic-packet.json');
writeFileSync(packetPath, packetText, 'utf8');
console.log(`  진단 패킷 저장: ${packetPath}`);

// ── 3. 에이전트 브리핑 생성 ──────────────────────────────────
const prompt = `너는 trusted-ceo-agent 플러그인의 ceo-risk-recon 오케스트레이터다.
아래 진단 패킷은 기업 식별 게이트를 통과한 ${companyName}(사업자등록번호 ${businessNumber})의
공시/거시(+제공 시 내부 CSV) 데이터에서 결정론으로 파생된 신호다.
패킷의 수치를 그대로 인용해(암산 금지) CEO 서명용 브리핑을 작성하라.

규칙:
- PRESENT 신호만 리스크로 다룬다. absent 신호를 리스크로 승격하면 오탐으로 채점된다.
- absent(미발화) 신호는 근거 수치(마커)를 인용하지 말고 "임계값 미달로 미발화" 수준의
  정성 서술로만 언급하라 — 미발화 신호의 수치를 한 절에 2개 이상 옮겨 적으면 포착(오탐)으로 채점된다.
- 리스크당: 결론 / 근거(수치+출처) / 왜 문제인가(인과 사슬 — 수치 나열 금지) / 확신도(확인됨|가설) / 판별 테스트 / 권고 조치.
- 마지막에 "확인 범위와 한계" 절. 단정 표현(분식입니다·확실합니다·명백한) 금지.
- 다른 설명 없이 마크다운 브리핑 본문만 출력하라.

[진단 패킷]
${packetText}`;
const promptPath = join(outDir, 'prompt.txt');
writeFileSync(promptPath, prompt, 'utf8');

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

const nextStepsPath = join(outDir, 'pipeline-next-steps.md');

if (!agentCmd) {
  writeFileSync(nextStepsPath, renderNextSteps({
    companyName, businessNumber, dataDir, outDir, identityPath, packetPath, briefingDone: false,
  }), 'utf8');
  console.log('에이전트 CLI 미감지(claude/codex) 또는 --agent none — 수동 절차로 전환합니다.');
  console.log(`  1) 프롬프트 파일을 에이전트에 전달: ${promptPath}`);
  console.log(`  2) 이후 단계 안내: ${nextStepsPath}`);
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
const briefingPath = join(outDir, 'briefing.md');
writeFileSync(briefingPath, agent.stdout, 'utf8');
console.log(`브리핑 저장: ${briefingPath} (${agent.stdout.length}자)`);

// ── 4. 자동 채점 ─────────────────────────────────────────────
step(4, `자동 채점 (briefing-eval${withJudge ? ' + LLM Judge' : ''})`);
const evalArgs = ['--signals-file', packetPath, ...(withJudge ? ['--judge'] : []), briefingPath];
const evaluation = spawnSync(process.execPath, [join(HERE, '..', 'test', 'briefing-eval.mjs'), ...evalArgs], {
  encoding: 'utf8', maxBuffer: 10 * 1024 * 1024,
});
process.stdout.write(evaluation.stdout);
if (evaluation.stderr) process.stderr.write(evaluation.stderr);

// ── 5. Word 보고서 렌더 (내장 zero-dependency — 인코딩·서식 코드 보장) ──
step(5, 'Word 보고서 렌더 (briefing.docx)');
const docxPath = join(outDir, 'briefing.docx');
writeFileSync(docxPath, briefingToDocx(agent.stdout, { date: new Date().toISOString().slice(0, 10) }));
console.log(`  DOCX 생성: ${docxPath}`);

writeFileSync(nextStepsPath, renderNextSteps({
  companyName, businessNumber, dataDir, outDir, identityPath, packetPath, briefingDone: true,
}), 'utf8');

console.log(`\nPIPELINE ${evaluation.status === 0 ? 'READY' : 'NEEDS REVIEW'}
identity: ${identityPath}
diagnosticPacket: ${packetPath}
briefing: ${briefingPath}
briefingDocx: ${docxPath}
nextSteps: ${nextStepsPath}`);
process.exit(evaluation.status ?? 1);
