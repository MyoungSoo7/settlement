#!/usr/bin/env node
/**
 * invest-copilot doctor — 설치/동기화 상태 진단 (읽기 전용, 아무것도 고치지 않음).
 *
 *  [1] 저장소 MCP 서버 왕복 → 기대 도구 목록(EXPECTED MCP TOOLS) 출력
 *  [2] Codex 설치본 드리프트 — 설치 매니페스트의 해시 vs 현재 소스 해시
 *  [3] $CODEX_HOME/config.toml MCP 블록 — 마커/경로 일치
 *  [4] git hooks — pre-commit 가드, post-merge/post-checkout 자동 재동기화
 *  [5] 루트 AGENTS.md 마커 블록 == 플러그인 AGENTS.md
 *  [6] 데이터 서비스 3종 (financial/economics/company) 헬스 — 내려가 있으면 도구가 실패한다
 *
 * 실행: node invest-copilot/scripts/doctor.mjs   (종료코드: 🔴 있으면 1)
 */
import { spawn, execSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve, isAbsolute } from 'node:path';
import { homedir } from 'node:os';

const PLUGIN_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = resolve(PLUGIN_DIR, '..');
const CODEX_HOME = process.env.CODEX_HOME ?? join(homedir(), '.codex');

let red = 0, yellow = 0;
const ok = m => console.log(`  \u{1F7E2} ${m}`);
const warn = m => { yellow++; console.log(`  \u{1F7E1} ${m}`); };
const bad = m => { red++; console.log(`  \u{1F534} ${m}`); };
const section = t => console.log(`\n[${t}]`);

const blobSha1 = buf => createHash('sha1')
  .update(Buffer.concat([Buffer.from(`blob ${buf.length}\0`), buf])).digest('hex');

function sourceFiles() {
  const files = ['AGENTS.md', 'mcp/server/index.mjs'];
  const glob = (dir, pred, map) => {
    const p = join(PLUGIN_DIR, dir);
    if (!existsSync(p)) return;
    for (const e of readdirSync(p, { withFileTypes: true })) {
      if (pred(e)) files.push(map(e.name));
    }
  };
  glob('commands', e => e.isFile() && e.name.endsWith('.md'), n => `commands/${n}`);
  glob('skills', e => e.isDirectory(), n => `skills/${n}/SKILL.md`);
  glob('hooks/guards', e => e.isFile() && e.name.endsWith('.mjs'), n => `hooks/guards/${n}`);
  glob('codex', e => e.isFile() && e.name.endsWith('.rules'), n => `codex/${n}`);
  return files.filter(rel => existsSync(join(PLUGIN_DIR, rel)));
}

async function checkServer() {
  section('1. MCP 서버 (저장소 기준 — 기대 상태)');
  const server = spawn(process.execPath, [join(PLUGIN_DIR, 'mcp', 'server', 'index.mjs')], {
    stdio: ['pipe', 'pipe', 'inherit'],
  });
  const responses = [];
  let buf = '';
  server.stdout.on('data', d => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (line) try { responses.push(JSON.parse(line)); } catch {}
    }
  });
  const send = m => server.stdin.write(JSON.stringify(m) + '\n');
  send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'doctor', version: '0' } } });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  await new Promise(r => setTimeout(r, 1200));
  server.kill();

  const init = responses.find(r => r.id === 1);
  const list = responses.find(r => r.id === 2);
  const version = init?.result?.serverInfo?.version;
  const tools = (list?.result?.tools ?? []).map(t => t.name);
  if (!version || tools.length === 0) {
    bad('저장소 MCP 서버가 응답하지 않음 — node 버전(18+)과 mcp/server/index.mjs 문법을 확인하세요.');
    return;
  }
  ok(`서버 v${version} — 도구 ${tools.length}개`);
  console.log(`\n  EXPECTED MCP TOOLS (${tools.length}) — 세션에 보이는 invest-copilot 도구와 비교하세요:`);
  console.log('  ' + tools.join(', '));
  console.log('  ↳ 세션에 누락된 도구가 있으면 실행 중인 MCP 서버가 구버전입니다.');
  console.log('    Claude Code: 재시작 또는 /mcp 재연결 · Codex CLI: 세션 재시작');
}

function checkManifest() {
  section(`2. Codex 설치본 드리프트 (CODEX_HOME=${CODEX_HOME})`);
  const mf = join(CODEX_HOME, '.invest-copilot-manifest.json');
  if (!existsSync(mf)) {
    warn('설치 매니페스트 없음 — install-codex.sh 를 아직 실행하지 않았습니다. → bash invest-copilot/install-codex.sh');
    return;
  }
  let m;
  try { m = JSON.parse(readFileSync(mf, 'utf8')); }
  catch { bad(`매니페스트 파싱 실패: ${mf}`); return; }

  const recorded = m.files ?? {};
  const stale = [];
  for (const [rel, hash] of Object.entries(recorded)) {
    const p = join(PLUGIN_DIR, rel);
    if (!existsSync(p)) { stale.push(`${rel} (소스에서 삭제됨)`); continue; }
    if (blobSha1(readFileSync(p)) !== hash) stale.push(rel);
  }
  const added = sourceFiles().filter(rel => !(rel in recorded));
  if (stale.length === 0 && added.length === 0) {
    ok(`설치본이 소스와 일치 (${Object.keys(recorded).length}개 파일, 설치: ${m.installedAt ?? '?'})`);
  } else {
    for (const s of stale) bad(`드리프트: ${s}`);
    for (const a of added) bad(`미설치 신규 파일: ${a}`);
    console.log('  ↳ 복구: bash invest-copilot/install-codex.sh (Codex 세션 재시작 필요)');
  }
}

function checkConfigToml() {
  section('3. Codex config.toml MCP 등록');
  const p = join(CODEX_HOME, 'config.toml');
  if (!existsSync(p)) { warn('config.toml 없음 — Codex 미사용 환경이면 무시해도 됩니다.'); return; }
  const body = readFileSync(p, 'utf8');
  if (!body.includes('[mcp_servers.invest-copilot]')) {
    warn('MCP 서버 미등록 — Codex 를 쓴다면 install-codex.sh 를 실행하세요.');
    return;
  }
  if (!body.includes('# invest-copilot:begin')) {
    warn('레거시(무마커) 블록 — install-codex.sh 재실행으로 마커 방식 전환을 권장합니다.');
  }
  const argMatch = body.match(/args\s*=\s*\[\s*"([^"]*invest-copilot[^"]*index\.mjs)"\s*\]/);
  if (!argMatch) { bad('args 에서 서버 경로를 찾지 못함 — 블록이 손상됐을 수 있습니다.'); return; }
  const cfgPath = argMatch[1].replace(/\\/g, '/');
  const norm = s => s.replace(/\\/g, '/').toLowerCase();
  if (!existsSync(cfgPath)) {
    bad(`등록된 서버 경로가 존재하지 않음: ${cfgPath} → install-codex.sh 재실행`);
  } else if (norm(cfgPath) !== norm(join(PLUGIN_DIR, 'mcp/server/index.mjs'))) {
    warn(`등록된 경로가 다른 체크아웃을 가리킴: ${cfgPath} (현재: ${PLUGIN_DIR}) — 의도한 것인지 확인하세요.`);
  } else {
    ok(`서버 경로 일치: ${cfgPath}`);
  }
}

function checkGitHooks() {
  section('4. git hooks');
  let hooksDir;
  try {
    hooksDir = execSync('git rev-parse --git-path hooks', { cwd: REPO_ROOT, encoding: 'utf8' }).trim();
    if (!isAbsolute(hooksDir)) hooksDir = join(REPO_ROOT, hooksDir);
  } catch { warn('git 저장소 아님 — hooks 점검 건너뜀'); return; }
  const expect = [
    ['pre-commit', '커밋 시 표현 컴플라이언스 가드'],
    ['post-merge', 'pull/merge 후 자동 재동기화'],
    ['post-checkout', '브랜치 전환 후 자동 재동기화'],
  ];
  for (const [name, why] of expect) {
    const p = join(hooksDir, name);
    if (existsSync(p) && readFileSync(p, 'utf8').includes('invest-copilot')) ok(`${name} — ${why}`);
    else warn(`${name} 미설치 (${why}) → bash invest-copilot/install-codex.sh`);
  }
}

function checkRootAgents() {
  section('5. 루트 AGENTS.md 코어 규칙 (Codex 상시 로드)');
  const p = join(REPO_ROOT, 'AGENTS.md');
  if (!existsSync(p)) {
    warn('루트 AGENTS.md 없음 — Codex 를 쓴다면 install-codex.sh 로 코어 규칙을 병합하세요.');
    return;
  }
  const body = readFileSync(p, 'utf8');
  const m = body.match(/<!-- invest-copilot:begin[^>]*-->\r?\n([\s\S]*?)<!-- invest-copilot:end -->/);
  if (!m) { warn('invest-copilot 마커 블록 없음 — install-codex.sh 로 병합하세요.'); return; }
  const src = readFileSync(join(PLUGIN_DIR, 'AGENTS.md'), 'utf8');
  const normalize = s => s.replace(/\r\n/g, '\n').trim();
  if (normalize(m[1]) === normalize(src)) ok('코어 규칙 블록이 플러그인 AGENTS.md 와 일치');
  else bad('코어 규칙 블록이 구버전 → bash invest-copilot/install-codex.sh 재실행');
}

async function checkServices() {
  section('6. 데이터 서비스 헬스 (도구 실사용 가능 여부)');
  const targets = [
    ['financial', process.env.FINANCIAL_BASE_URL ?? 'http://localhost:8086'],
    ['economics', process.env.ECONOMICS_BASE_URL ?? 'http://localhost:8087'],
    ['company', process.env.COMPANY_BASE_URL ?? 'http://localhost:8090'],
  ];
  for (const [name, base] of targets) {
    try {
      const res = await fetch(`${base}/actuator/health`, { signal: AbortSignal.timeout(3000) });
      if (res.ok) ok(`${name} (${base}) — UP`);
      else warn(`${name} (${base}) — HTTP ${res.status}`);
    } catch {
      warn(`${name} (${base}) — DOWN/미기동 (docker compose up 또는 bootRun 필요, 도구 호출은 실패함)`);
    }
  }
}

console.log(`invest-copilot doctor — plugin: ${PLUGIN_DIR}`);
await checkServer();
checkManifest();
checkConfigToml();
checkGitHooks();
checkRootAgents();
await checkServices();

console.log(`\n결과: \u{1F534} ${red} · \u{1F7E1} ${yellow}${red === 0 && yellow === 0 ? ' — ALL GREEN' : ''}`);
process.exit(red > 0 ? 1 : 0);
