#!/usr/bin/env node
/**
 * settlement-copilot 스모크 테스트 (네트워크 불필요).
 *  1) MCP 서버: initialize → tools/list → tools/call settlement_simulate 왕복 검증
 *  2) 가드 규칙: money/immutable-history/pii/prod-db 케이스 검증
 * 실행: node settlement-copilot/test/smoke.mjs
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
  send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'settlement_simulate', arguments: { amount: '1000000', tier: 'NORMAL' } } });

  await new Promise(r => setTimeout(r, 1500));
  server.kill();

  const init = responses.find(r => r.id === 1);
  const list = responses.find(r => r.id === 2);
  const call = responses.find(r => r.id === 3);

  assert('initialize 응답', init?.result?.serverInfo?.name === 'settlement-copilot');
  assert('tools/list 7개 도구', list?.result?.tools?.length === 7,
    `got ${list?.result?.tools?.length}`);

  const sim = call && JSON.parse(call.result.content[0].text);
  assert('simulate: NORMAL 1,000,000 → fee 35,000', sim?.fee === '35000', `got ${sim?.fee}`);
  assert('simulate: net 965,000', sim?.netAmount === '965000', `got ${sim?.netAmount}`);
  assert('simulate: holdback 289,500 (30%)', sim?.holdback?.amount === '289500', `got ${sim?.holdback?.amount}`);
  assert('simulate: 즉시지급 675,500', sim?.immediatePayout === '675500', `got ${sim?.immediatePayout}`);
}

// ── 2. Guard rules ───────────────────────────────────────────────────────────
function testGuards() {
  console.log('[2] Guard rules');

  const bad = checkFileContent(
    'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/FeeCalc.java',
    'double fee = amount * 0.035;\nBigDecimal r = new BigDecimal(0.035);\n');
  assert('money: double fee 차단', bad.some(v => v.rule === 'money-type-guard' && v.severity === 'BLOCK'));
  assert('money: BigDecimal(double) 차단', bad.filter(v => v.rule === 'money-type-guard').length >= 2);

  const good = checkFileContent(
    'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/FeeCalc.java',
    'BigDecimal fee = amount.multiply(rate).setScale(0, RoundingMode.HALF_UP);\n');
  assert('money: BigDecimal 정상 통과', good.length === 0, JSON.stringify(good));

  const hist = checkFileContent('x/repo/Q.java',
    'em.createQuery("UPDATE settlements SET commission_rate = :r");\nsettlement.setCommissionRate(newRate);\n');
  assert('history: settlements UPDATE 차단', hist.some(v => v.rule === 'immutable-history-guard'));
  assert('history: setCommissionRate 차단', hist.filter(v => v.rule === 'immutable-history-guard').length >= 2);

  const pii = checkFileContent('x/PayoutService.java',
    'log.info("payout to accountNumber={}", accountNumber);\n');
  assert('pii: 계좌번호 로깅 차단', pii.some(v => v.rule === 'pii-logging-guard' && v.severity === 'BLOCK'));

  const piiOk = checkFileContent('x/PayoutService.java',
    'log.info("payout to account={}", masker.maskAccount(accountNumber));\n');
  assert('pii: 마스킹 경유 통과', !piiOk.some(v => v.rule === 'pii-logging-guard'));

  const cmd = checkCommand('psql -h db.prod -d settlement_db -c "UPDATE settlements SET amount=0"');
  assert('prod-db: 직접 UPDATE 차단', cmd.some(v => v.rule === 'prod-db-guard' && v.severity === 'BLOCK'));

  const cmdOk = checkCommand('git status');
  assert('command: 일반 명령 통과', cmdOk.length === 0);
}

await testMcp();
testGuards();

console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
