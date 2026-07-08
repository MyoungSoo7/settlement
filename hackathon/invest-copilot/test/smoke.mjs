#!/usr/bin/env node
/**
 * invest-copilot 스모크 테스트 (네트워크 불필요).
 *  1) MCP 서버: initialize → tools/list → guard_check 왕복 검증
 *  2) 가드 규칙: 보장/단정 표현·고지문·조회 DB 쓰기 차단·통과 케이스
 * 실행: node invest-copilot/test/smoke.mjs
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { checkFileContent, checkCommand } from '../hooks/guards/rules.mjs';

const here = dirname(fileURLToPath(import.meta.url));
let failures = 0;

function assert(name, cond, detail = '') {
  if (cond) console.log(`  ok  ${name}`);
  else { failures++; console.error(`FAIL  ${name} ${detail}`); }
}

// ── 1. MCP server round-trip ─────────────────────────────────────────────────
async function testMcp() {
  console.log('[1] MCP server round-trip');
  const server = spawn(process.execPath, [join(here, '..', 'mcp', 'server', 'index.mjs')], {
    stdio: ['pipe', 'pipe', 'inherit'],
  });

  const responses = [];
  let buf = '';
  server.stdout.on('data', d => {
    buf += d;
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (line) responses.push(JSON.parse(line));
    }
  });

  const send = m => server.stdin.write(JSON.stringify(m) + '\n');
  send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'smoke', version: '0' } } });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'guard_check', arguments: { file_path: 'report.md', content: '이 종목은 수익 보장입니다.' } } });
  send({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'guard_check', arguments: { command: 'git status' } } });

  await new Promise(r => setTimeout(r, 1500));
  server.kill();

  const init = responses.find(r => r.id === 1);
  const list = responses.find(r => r.id === 2);

  assert('initialize 응답', init?.result?.serverInfo?.name === 'invest-copilot');
  assert('tools/list 9개 도구', list?.result?.tools?.length === 9,
    `got ${list?.result?.tools?.length}`);
  const toolNames = (list?.result?.tools ?? []).map(t => t.name);
  for (const t of ['company_search', 'fin_statements', 'fin_metrics', 'econ_latest', 'econ_series',
    'news_recent', 'reputation_score', 'invest_signal', 'guard_check']) {
    assert(`도구 등록: ${t}`, toolNames.includes(t), `missing ${t}`);
  }

  const gb = JSON.parse(responses.find(r => r.id === 3).result.content[0].text);
  assert('guard_check: 보장 표현 blocked', gb?.blocked === true && gb?.mode === 'file', JSON.stringify(gb));
  const go = JSON.parse(responses.find(r => r.id === 4).result.content[0].text);
  assert('guard_check: 일반 명령 통과', go?.blocked === false && go?.violations?.length === 0, JSON.stringify(go));
}

// ── 2. Guard rules ───────────────────────────────────────────────────────────
function testGuards() {
  console.log('[2] Guard rules');

  const bad = checkFileContent('advice.md',
    '무조건 오른다고 확신합니다.\n원금 보장 상품입니다.\n목표 주가는 5만원입니다.\n');
  assert('claims: 무조건 오른다 차단', bad.some(v => v.rule === 'forbidden-claims-guard' && v.severity === 'BLOCK'));
  assert('claims: 원금 보장 차단', bad.filter(v => v.rule === 'forbidden-claims-guard').length >= 2);
  assert('price: 목표주가 경고', bad.some(v => v.rule === 'no-price-data-guard' && v.severity === 'WARN'));

  const quoted = checkFileContent('doc.md',
    '> 원금 보장 이라는 표현은 금지다\n| "수익 보장" | 금지 표현 |\n');
  assert('claims: 인용/금지목록 문맥 통과', quoted.length === 0, JSON.stringify(quoted));

  const java = checkFileContent('x/Service.java', 'String banned = "수익 보장";');
  assert('claims: 코드 파일 미검사', java.length === 0);

  const noDisc = checkFileContent('r.md', '매수 체크리스트 BUY-8 결과 7개 충족입니다.');
  assert('disclaimer: 고지문 누락 경고', noDisc.some(v => v.rule === 'disclaimer-guard' && v.severity === 'WARN'));

  const withDisc = checkFileContent('r2.md',
    '매수 체크리스트 7개 충족.\n본 정보는 교육 목적이며 투자자문이 아닙니다. 투자 판단과 책임은 투자자 본인에게 있습니다.\n');
  assert('disclaimer: 고지문 포함 통과', withDisc.length === 0, JSON.stringify(withDisc));

  const cmd = checkCommand('psql -h db -d lemuel_financial -c "TRUNCATE financial_statements"');
  assert('readonly-db: 쓰기 차단', cmd.some(v => v.rule === 'readonly-db-guard' && v.severity === 'BLOCK'));
  assert('readonly-db: SELECT 통과',
    checkCommand('psql -d lemuel_economics -c "SELECT * FROM indicator_values LIMIT 5"').length === 0);
  assert('command: 일반 명령 통과', checkCommand('git status').length === 0);
}

await testMcp();
testGuards();

console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
