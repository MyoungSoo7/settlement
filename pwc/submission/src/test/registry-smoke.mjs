#!/usr/bin/env node
/**
 * 국세청 사업자등록정보 MCP 세팅 스모크 테스트.
 *  [1] MCP 서버 왕복: initialize → tools/list(4) → registry_status
 *  [2] 로컬 체크섬 검증은 키 없이도 실행
 *  [3] (DATA_GO_KR_API_KEY 있을 때만) 라이브: business_status_check
 * 실행: node src/test/registry-smoke.mjs
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  callMcpServer,
  createAssert,
  initializedNotification,
  initializeRequest,
  listToolsRequest,
  parseToolPayload,
  toolCall,
} from './smoke-harness.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const { check, finish } = createAssert();

const responses = await callMcpServer(join(here, '..', 'mcp', 'registry-server.mjs'), [
  initializeRequest,
  initializedNotification,
  listToolsRequest,
  toolCall(3, 'registry_status'),
  toolCall(4, 'business_number_validate', { b_no: '124-81-00998' }),
  toolCall(5, 'business_status_check', { b_no: '124-81-00998' }),
]);

const init = responses.find(r => r.id === 1);
const list = responses.find(r => r.id === 2);
const status = responses.find(r => r.id === 3);
const local = responses.find(r => r.id === 4);
const live = responses.find(r => r.id === 5);

check('initialize 응답', init?.result?.serverInfo?.name === 'trusted-ceo-agent-registry');
check('tools/list 5개 도구', list?.result?.tools?.length === 5, `got ${list?.result?.tools?.length}`);
for (const t of ['business_number_validate', 'business_status_check', 'business_auth_check', 'company_identity_gate', 'registry_status']) {
  check(`도구 등록: ${t}`, (list?.result?.tools ?? []).some(x => x.name === t), `missing ${t}`);
}

const st = parseToolPayload(status);
check('registry_status 응답', st?.apiKey === 'present' || String(st?.apiKey).startsWith('missing'));

const localResult = parseToolPayload(local);
check('로컬 체크섬 검증', localResult?.valid === true && localResult?.normalized === '1248100998');

if (st?.apiKey === 'present') {
  const sr = parseToolPayload(live);
  check('라이브: 사업자 상태조회 응답 형식',
    Array.isArray(sr?.data) || Array.isArray(sr?.response?.body?.items?.item),
    JSON.stringify(sr));
} else {
  console.log('  (키 없음 — 라이브 검증 생략)');
}

finish();
