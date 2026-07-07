#!/usr/bin/env node
/**
 * ECOS 세팅 스모크 테스트.
 *  [1] MCP 서버 왕복: initialize → tools/list(4) → ecos_status
 *  [2] (ECOS_API_KEY 있을 때만) 라이브: ecos_indicator BASE_RATE → 관측치 ≥ 1
 * 실행: node src/test/ecos-smoke.mjs
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

const server = spawn(process.execPath, [join(here, '..', 'mcp', 'ecos-server.mjs')], {
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
send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'ecos_status', arguments: {} } });
send({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'ecos_indicator', arguments: { code: 'BASE_RATE', months_back: 2 } } });

await new Promise(r => setTimeout(r, 8000));
server.kill();

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const series = responses.find(r => r.id === 4);

assert('initialize 응답', init?.result?.serverInfo?.name === 'trusted-ceo-agent-ecos');
assert('tools/list 4개 도구', list?.result?.tools?.length === 4, `got ${list?.result?.tools?.length}`);
for (const t of ['ecos_indicator', 'ecos_series', 'ecos_key_stats', 'ecos_status']) {
  assert(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

const st = status && JSON.parse(status.result.content[0].text);
assert('ecos_status 응답', st?.apiKey === 'present' || String(st?.apiKey).startsWith('missing'));
assert('카탈로그 4개 지표', Object.keys(st?.catalog ?? {}).length === 4);

if (st?.apiKey === 'present') {
  const sr = series && JSON.parse(series.result.content[0].text);
  assert('라이브: BASE_RATE 관측치 ≥ 1',
    sr?.count >= 1 && typeof sr?.latest?.value === 'number',
    JSON.stringify({ count: sr?.count, latest: sr?.latest }));
} else {
  console.log('  (키 없음 — 라이브 검증 생략)');
}

console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
