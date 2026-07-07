#!/usr/bin/env node
/**
 * DART 세팅 스모크 테스트.
 *  [1] MCP 서버 왕복: initialize → tools/list(6) → dart_status
 *  [2] (DART_API_KEY 있을 때만) 라이브: corp_search 삼성전자 → 00126380
 * 실행: node src/test/dart-smoke.mjs
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
let failures = 0;
const assert = (name, cond, detail = '') => {
  if (cond) console.log(`  ok  ${name}`);
  else { failures++; console.error(`FAIL  ${name} ${detail}`); }
};

const server = spawn(process.execPath, [join(here, '..', 'mcp', 'dart-server.mjs')], {
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
    if (line) responses.push(JSON.parse(line));
  }
});
const send = m => server.stdin.write(JSON.stringify(m) + '\n');

send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'smoke', version: '0' } } });
send({ jsonrpc: '2.0', method: 'notifications/initialized' });
send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'dart_status', arguments: {} } });
send({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'dart_corp_search', arguments: { keyword: '삼성전자' } } });

await new Promise(r => setTimeout(r, 8000));
server.kill();

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const search = responses.find(r => r.id === 4);

assert('initialize 응답', init?.result?.serverInfo?.name === 'trusted-ceo-agent-dart');
assert('tools/list 6개 도구', list?.result?.tools?.length === 6, `got ${list?.result?.tools?.length}`);
for (const t of ['dart_corp_search', 'dart_company', 'dart_disclosures',
  'dart_financial_summary', 'dart_financial_full', 'dart_status']) {
  assert(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

const st = status && JSON.parse(status.result.content[0].text);
assert('dart_status 응답', st?.apiKey === 'present' || String(st?.apiKey).startsWith('missing'));

if (st?.apiKey === 'present') {
  const sr = search && JSON.parse(search.result.content[0].text);
  assert('라이브: 삼성전자 → 00126380',
    sr?.matches?.some(m => m.corpCode === '00126380' && m.stockCode === '005930'),
    JSON.stringify(sr?.matches?.slice(0, 2)));
} else {
  console.log('  (키 없음 — 라이브 검증 생략)');
}

console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
