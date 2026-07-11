#!/usr/bin/env node
/**
 * doctor — 설치 직후 환경 진단 (네트워크 0, 한 명령).
 *
 * 심사/최초 사용자가 "지금 무엇이 되고 무엇이 안 되는지"를 30초 안에 알게 한다:
 *   1. Node 버전 (>=22 — zero-dependency 런타임 전제)
 *   2. API 키 상태 — 축별로 무엇이 열리는지 (없어도 어디까지 동작하는지 명시)
 *   3. 오프라인 셀프테스트 — 동봉 샘플로 게이트(INV-1~7) + 내부 신호 파생 실행
 *   4. 에이전트 CLI 감지 — claude -p / codex exec (demo-e2e·파이프라인과 동일 규칙)
 *   5. MCP 배선 — .mcp.json 의 서버 엔트리가 실제 파일을 가리키는지
 *
 * 설계: 키가 없는 축은 경고일 뿐 실패가 아니다(씨드 폴백·프롬프트 폴백이 있으므로).
 * exit 1 은 "플러그인 자체가 깨진 상태"(Node 미달, 셀프테스트 실패, MCP 배선 오류)만.
 *
 * 사용:
 *   node src/bin/doctor.mjs          # 사람용 리포트 + 다음 명령 안내
 *   node src/bin/doctor.mjs --json   # 기계용 (CI/스크립트)
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { findEnvKey, safeErrorMessage } from '../common/env.mjs';
import { loadBooks, runInvariants } from '../common/books.mjs';
import { deriveSignals } from '../common/signals.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC_DIR = resolve(HERE, '..');
const SAMPLE_DIR = join(SRC_DIR, 'data', 'sample');
const asJson = process.argv.includes('--json');

// 축별 키 정의 — 키가 없을 때 무엇이 안 되는지(그리고 무엇은 되는지)를 함께 말한다.
const KEY_AXES = [
  { key: 'DART_API_KEY', axis: '기본 모드 (E1~E5·E8 공시 진단)', required: false, without: '라이브 진단 불가 — 동봉 샘플·픽스처 데모는 그대로 동작' },
  { key: 'ECOS_API_KEY', axis: '거시 컨텍스트 (기준금리)', required: false, without: '거시 절 생략 (진단은 계속됨)' },
  { key: 'NAVER_CLIENT_ID', axis: '뉴스 축 (--with-news)', required: false, without: '뉴스 신호 생략' },
  { key: 'NAVER_CLIENT_SECRET', axis: '뉴스 축 (--with-news)', required: false, without: '뉴스 신호 생략' },
  { key: 'KRX_API_KEY', axis: '시장 축 E6·E7 (--with-market)', required: false, without: '시장 신호 생략' },
  { key: 'GEMINI_API_KEY', axis: 'LLM Judge 2차 채점 (--judge)', required: false, without: 'Judge 자동 skip — 규칙 채점(1차)은 그대로' },
  { key: 'ANTHROPIC_API_KEY', axis: 'LLM Judge 대체 프로바이더', required: false, without: 'GEMINI 키가 있으면 불필요' },
];

const report = { node: {}, keys: [], selfTest: {}, agent: {}, mcp: {}, ready: {} };
let broken = false;

// 1. Node 버전
{
  const major = Number(process.versions.node.split('.')[0]);
  report.node = { version: process.versions.node, ok: major >= 22 };
  if (!report.node.ok) broken = true;
}

// 2. API 키 상태 (존재 여부만 — 값 검증은 라이브 호출의 몫)
for (const spec of KEY_AXES) {
  report.keys.push({ ...spec, found: Boolean(findEnvKey(spec.key, HERE)) });
}

// 3. 오프라인 셀프테스트 — 동봉 샘플로 게이트+신호 엔진이 실제로 돈다는 증명 (네트워크 0)
try {
  const books = loadBooks(SAMPLE_DIR);
  const gate = runInvariants(books);
  const signals = deriveSignals(books);
  report.selfTest = {
    ok: gate.gate === 'PASS',
    gate: gate.gate,
    checks: gate.checks.length,
    presentSignals: signals.filter((s) => s.present).map((s) => s.id),
  };
} catch (e) {
  report.selfTest = { ok: false, error: safeErrorMessage(e) };
}
if (!report.selfTest.ok) broken = true;

// 4. 에이전트 CLI 감지 — demo-e2e/파이프라인과 동일 규칙 (claude → codex 순)
{
  let detected = null;
  for (const [probe, cmd] of [['claude', 'claude -p'], ['codex', 'codex exec']]) {
    const check = spawnSync(probe, ['--version'], { encoding: 'utf8', shell: process.platform === 'win32' });
    if (check.status === 0) { detected = cmd; break; }
  }
  report.agent = { detected, fallback: detected ? null : 'prompt.txt 저장 + 수동 절차 안내 (파이프라인이 자동 폴백)' };
}

// 5. MCP 배선 — .mcp.json 의 각 서버가 실제 파일을 가리키는지
{
  const mcpPath = join(SRC_DIR, '.mcp.json');
  try {
    const conf = JSON.parse(readFileSync(mcpPath, 'utf8'));
    const servers = Object.entries(conf.mcpServers ?? {}).map(([name, s]) => {
      const target = join(SRC_DIR, ...(s.args?.[0] ?? '').split('/'));
      return { name, entry: s.args?.[0] ?? null, exists: existsSync(target) };
    });
    report.mcp = { ok: servers.length > 0 && servers.every((s) => s.exists), servers };
  } catch (e) {
    report.mcp = { ok: false, error: safeErrorMessage(e) };
  }
  if (!report.mcp.ok) broken = true;
}

// 종합 — 모드별 준비 상태
const hasKey = (k) => report.keys.find((s) => s.key === k)?.found;
report.ready = {
  offlineDemo: report.selfTest.ok === true,
  basicMode: report.selfTest.ok === true && Boolean(hasKey('DART_API_KEY')),
  judge: Boolean(hasKey('GEMINI_API_KEY') || hasKey('ANTHROPIC_API_KEY')),
  newsAxis: Boolean(hasKey('NAVER_CLIENT_ID') && hasKey('NAVER_CLIENT_SECRET')),
  marketAxis: Boolean(hasKey('KRX_API_KEY')),
  broken,
};

if (asJson) {
  console.log(JSON.stringify(report, null, 2));
} else {
  const mark = (ok) => (ok ? 'ok ' : 'X  ');
  console.log('=== trusted-ceo-agent doctor — 환경 진단 (네트워크 0) ===');
  console.log(`${mark(report.node.ok)}Node ${report.node.version}${report.node.ok ? '' : '  ← Node 22+ 필요'}`);
  console.log(`${mark(report.selfTest.ok)}오프라인 셀프테스트 — 게이트 ${report.selfTest.gate ?? 'ERROR'}${report.selfTest.ok ? ` (불변식 ${report.selfTest.checks}종, 샘플 신호 ${report.selfTest.presentSignals.join('/')})` : ` — ${report.selfTest.error ?? ''}`}`);
  console.log(`${mark(report.mcp.ok)}MCP 배선 — ${report.mcp.ok ? `${report.mcp.servers.length}개 서버 파일 확인` : (report.mcp.error ?? '엔트리 파일 누락')}`);
  console.log(`${report.agent.detected ? 'ok ' : '-  '}에이전트 CLI — ${report.agent.detected ?? `미감지 (${report.agent.fallback})`}`);
  console.log('\n[API 키 — 축별 상태]');
  for (const s of report.keys) {
    console.log(`  ${s.found ? 'ok ' : '-  '}${s.key.padEnd(22)} ${s.axis}${s.found ? '' : ` — 없으면: ${s.without}`}`);
  }
  console.log('\n[지금 바로 실행 가능한 것]');
  if (report.ready.offlineDemo) console.log('  · 오프라인 데모:  node src/bin/demo-e2e.mjs --agent none');
  if (report.ready.basicMode) console.log('  · 기업 진단:      node src/bin/diagnose-company.mjs --company 삼성전자');
  if (report.ready.basicMode) console.log(`  · 통합 파이프라인: node src/bin/ceo-consulting-pipeline.mjs --company 삼성전자 --business-number 124-81-00998${report.ready.judge ? ' --judge' : ''}`);
  if (!report.ready.basicMode) console.log('  · DART_API_KEY 를 .env 에 추가하면 기업명만으로 라이브 진단이 열립니다 (https://opendart.fss.or.kr)');
  console.log(broken ? '\n진단 결과: 문제 있음 (위 X 항목 참조)' : '\n진단 결과: 정상');
}

if (broken) process.exitCode = 1;
