import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode } from './helpers/proc.mjs';

/**
 * 업로드 서버가 같은 테스트 프로세스에 떠 있으므로 spawnSync(runNode)를 쓰면
 * 부모 이벤트 루프가 막혀 서버가 응답하지 못하는 교착이 된다 — 반드시 비동기 spawn.
 */
function runNodeAsync(args, env = {}) {
  return new Promise((resolveDone) => {
    const child = spawn(process.execPath, args, { env: { ...process.env, ...env } });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('close', (status) => resolveDone({ status, stdout, stderr }));
  });
}

const HERE = dirname(fileURLToPath(import.meta.url));
const BATCH_CLI = join(HERE, '..', '..', 'bin', 'quarterly-briefing-batch.mjs');
const FAKE_PIPELINE = join(HERE, 'helpers', 'fake-pipeline.mjs');
const ROOT = join(HERE, '..', '..', '..');

const PERIOD = '2026Q2';

function companiesFile(dir, list) {
  const path = join(dir, 'companies.json');
  writeFileSync(path, JSON.stringify(list), 'utf8');
  return path;
}

/**
 * 업로드 요청(multipart raw)을 수집하는 최소 HTTP 서버 — 파싱은 문자열 검사로 충분.
 * failFirst: 첫 N개 요청을 소켓 절단으로 실패시켜 stale keep-alive reset 재시도 경로를 재현한다.
 */
function startUploadServer({ failFirst = 0 } = {}) {
  const received = [];
  let dropped = 0;
  return new Promise((resolveStart) => {
    const server = createServer((req, res) => {
      if (dropped < failFirst) {
        dropped += 1;
        req.socket.destroy();
        return;
      }
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        received.push({
          url: req.url,
          apiKey: req.headers['x-internal-api-key'] ?? null,
          body,
        });
        // 기업 마스터 일괄 등록은 카운트 JSON 으로 응답, 문서 업로드는 201 + id
        if (req.url.endsWith('/admin/company/companies')) {
          const count = (() => { try { return JSON.parse(body).companies.length; } catch { return 0; } })();
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ received: count, registered: count, updated: 0, skipped: 0 }));
          return;
        }
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: received.length }));
      });
    });
    server.listen(0, '127.0.0.1', () => {
      resolveStart({
        received,
        base: `http://127.0.0.1:${server.address().port}`,
        // undici(fetch) keep-alive 소켓이 close() 를 무한 대기시키므로 강제 절단
        close: () => new Promise((r) => { server.closeAllConnections(); server.close(r); }),
      });
    });
  });
}

// 배치 산출 루트(outputs/batch/<PERIOD>)는 실산출물과 같은 위치 — 테스트 후 기업 폴더만 정리
function cleanupBatchDirs(names) {
  for (const name of names) {
    rmSync(join(ROOT, 'outputs', 'batch', PERIOD, name), { recursive: true, force: true });
  }
}

test('배치 — PASS 기업만 기간 스탬프 docx+png 업로드, FAIL 기업은 보고 후 exit 1', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['성공전자', '실패전자'];
  const server = await startUploadServer();
  try {
    const companies = companiesFile(dir, [
      { name: '성공전자', stockCode: '111111', businessNumber: '123-45-67891' },
      { name: '실패전자', stockCode: '222222', businessNumber: '123-45-67891' },
    ]);
    const run = await runNodeAsync([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--upload', server.base,
    ], { FAKE_PIPELINE_FAIL_FOR: '실패전자', COMPANY_INTERNAL_API_KEY: 'test-key' });

    assert.equal(run.status, 1, `실패 기업이 있으면 exit 1: ${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /성공전자 \(111111\): PASS, 업로드 2건/);
    assert.match(run.stdout, /실패전자 \(222222\): 실패 — 파이프라인 exit 1/);
    assert.match(run.stdout, /2개사 중 성공 1 \/ 실패 1/);

    // 업로드는 PASS 기업의 docx+png 2건뿐 — 기간 스탬프 파일명 + 시크릿 헤더 + stockCode 필드
    assert.equal(server.received.length, 2);
    for (const r of server.received) {
      assert.equal(r.url, '/admin/company/documents');
      assert.equal(r.apiKey, 'test-key');
      assert.match(r.body, /name="stockCode"[\r\n]+[\r\n]+111111/);
    }
    assert.match(server.received[0].body, new RegExp(`filename="성공전자-CEO-브리핑-${PERIOD}\\.docx"`));
    assert.match(server.received[1].body, new RegExp(`filename="성공전자-경영진-스냅샷-${PERIOD}\\.png"`));

    // 기간 스탬프 사본이 산출 폴더에 남는다 (이력 보존)
    assert.ok(existsSync(join(ROOT, 'outputs', 'batch', PERIOD, '성공전자', `성공전자-CEO-브리핑-${PERIOD}.docx`)));
  } finally {
    await server.close();
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — --no-upload 는 스탬프 사본만 만들고 성공 종료, PNG 부재 시 docx 만', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['오프라인전자'];
  try {
    const companies = companiesFile(dir, [
      { name: '오프라인전자', stockCode: '333333', businessNumber: '123-45-67891' },
    ]);
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload',
    ], { FAKE_PIPELINE_NO_PNG: '1' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /업로드 생략 \(--no-upload\)/);
    const outDir = join(ROOT, 'outputs', 'batch', PERIOD, '오프라인전자');
    assert.ok(existsSync(join(outDir, `오프라인전자-CEO-브리핑-${PERIOD}.docx`)));
    assert.ok(!existsSync(join(outDir, `오프라인전자-경영진-스냅샷-${PERIOD}.png`)));
    assert.ok(existsSync(join(outDir, 'pipeline.log')));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — --only 필터는 지정 기업만 실행한다 (종목코드로도 매칭)', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['하나전자', '두나전자'];
  try {
    const companies = companiesFile(dir, [
      { name: '하나전자', stockCode: '444444', businessNumber: '123-45-67891' },
      { name: '두나전자', stockCode: '555555', businessNumber: '123-45-67891' },
    ]);
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload', '--only', '555555',
    ]);

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /대상 1개사/);
    assert.ok(existsSync(join(ROOT, 'outputs', 'batch', PERIOD, '두나전자')));
    assert.ok(!existsSync(join(ROOT, 'outputs', 'batch', PERIOD, '하나전자')));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — 업로드 소켓 절단(stale keep-alive)은 재시도로 회복한다', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['재시도전자'];
  const server = await startUploadServer({ failFirst: 1 });
  try {
    const companies = companiesFile(dir, [
      { name: '재시도전자', stockCode: '888888', businessNumber: '123-45-67891' },
    ]);
    const run = await runNodeAsync([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--upload', server.base,
    ], { FAKE_PIPELINE_NO_PNG: '1' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /업로드 재시도 2\/3/);
    assert.match(run.stdout, /재시도전자 \(888888\): PASS, 업로드 1건/);
    assert.equal(server.received.length, 1);
  } finally {
    await server.close();
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — 기간 파생: 기본값은 직전 분기 (Q1 은 전년 Q4 로 롤백)', () => {
  const mid = runNode([BATCH_CLI, '--print-period'], { BATCH_TODAY: '2026-07-11' });
  assert.equal(mid.stdout.trim(), '2026Q2');
  const rollback = runNode([BATCH_CLI, '--print-period'], { BATCH_TODAY: '2026-01-15' });
  assert.equal(rollback.stdout.trim(), '2025Q4');
  const explicit = runNode([BATCH_CLI, '--print-period', '--period', '2030Q3']);
  assert.equal(explicit.stdout.trim(), '2030Q3');
  const invalid = runNode([BATCH_CLI, '--print-period', '--period', '2030-3분기']);
  assert.equal(invalid.status, 1);
});

test('배치 — 목록 필수 필드 누락은 해당 기업만 건너뛰고 보고한다', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['정상전자'];
  try {
    const companies = companiesFile(dir, [
      { name: '무번호전자', stockCode: '666666' },
      { name: '정상전자', stockCode: '777777', businessNumber: '123-45-67891' },
    ]);
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload',
    ]);

    assert.equal(run.status, 1);
    assert.match(run.stdout, /무번호전자 \(666666\): 실패 — 목록 항목에 name\/stockCode\/businessNumber/);
    assert.match(run.stdout, /정상전자 \(777777\): PASS/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
    rmSync(join(ROOT, 'outputs', 'batch', PERIOD, '무번호전자'), { recursive: true, force: true });
  }
});

test('배치 — --register 는 업로드 전 기업 마스터에 일괄 등록한다', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['등록전자'];
  const server = await startUploadServer();
  try {
    const companies = companiesFile(dir, [
      { name: '등록전자', stockCode: '101010', businessNumber: '123-45-67891', corpCode: '00111111', market: 'KOSDAQ' },
    ]);
    const run = await runNodeAsync([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--upload', server.base, '--register', server.base,
    ], { FAKE_PIPELINE_NO_PNG: '1', COMPANY_INTERNAL_API_KEY: 'test-key' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /기업 마스터 등록: 요청 1 · 신규 1/);
    // 등록 요청이 업로드보다 먼저, companies 배열에 stockCode·market 포함
    const register = server.received.find((r) => r.url.endsWith('/admin/company/companies'));
    assert.ok(register, '기업 마스터 등록 요청이 있어야 한다');
    assert.equal(register.apiKey, 'test-key');
    assert.match(register.body, /"stockCode":"101010"/);
    assert.match(register.body, /"market":"KOSDAQ"/);
    // 그리고 문서 업로드도 수행
    assert.ok(server.received.some((r) => r.url === '/admin/company/documents'));
  } finally {
    await server.close();
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — --resume 는 기간 스탬프 docx 가 이미 있으면 파이프라인을 건너뛴다', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['재개전자'];
  const outDir = join(ROOT, 'outputs', 'batch', PERIOD, '재개전자');
  try {
    // 이전 실행에서 완료된 것처럼 스탬프 docx 를 미리 만들어 둔다
    mkdirSync(outDir, { recursive: true });
    writeFileSync(join(outDir, `재개전자-CEO-브리핑-${PERIOD}.docx`), 'stub', 'utf8');
    const companies = companiesFile(dir, [
      { name: '재개전자', stockCode: '121212', businessNumber: '123-45-67891' },
    ]);
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload', '--resume',
    ]);

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /이미 완료 — 건너뜀 \(--resume\)/);
    assert.match(run.stdout, /재개전자 \(121212\): 이미 완료 — 건너뜀 \(resume\)/);
    assert.match(run.stdout, /1개사 중 성공 0 \/ 실패 0 \/ 건너뜀 1/);
    // 파이프라인이 안 돌았으므로 pipeline.log 는 생성되지 않는다
    assert.ok(!existsSync(join(outDir, 'pipeline.log')));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — 유니버스 빌더 산출물({companies:[...]} 객체)도 목록으로 수용한다', () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['유니버스전자'];
  try {
    // build-briefing-universe.mjs 산출물 형태 — 최상위 배열이 아니라 companies 키를 가진 객체
    const path = join(dir, 'universe.json');
    writeFileSync(path, JSON.stringify({
      generatedAt: '2026-07-11T00:00:00Z', markets: ['KOSPI', 'KOSDAQ'], total: 1, count: 1,
      companies: [{ name: '유니버스전자', stockCode: '131313', businessNumber: '123-45-67891' }],
      skipped: [],
    }), 'utf8');
    const run = runNode([
      BATCH_CLI, '--companies', path, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload',
    ], { FAKE_PIPELINE_NO_PNG: '1' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /대상 1개사/);
    assert.match(run.stdout, /유니버스전자 \(131313\): PASS/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — --escalate-signals 는 --agent 와 동시 지정 시 즉시 실패한다 (형식 오류 포함)', () => {
  const conflict = runNode([BATCH_CLI, '--escalate-signals', '2', '--agent', 'none', '--print-period']);
  assert.equal(conflict.status, 1);
  assert.match(conflict.stderr, /--agent 와 함께 쓸 수 없습니다/);
  const invalid = runNode([BATCH_CLI, '--escalate-signals', '0', '--print-period']);
  assert.equal(invalid.status, 1);
  assert.match(invalid.stderr, /1 이상의 정수/);
});

test('배치 — 승격 임계 미만은 1차 로컬 브리핑 한 번만 실행한다 (--agent none 강제)', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['건전전자'];
  const callsPath = join(dir, 'calls.jsonl');
  try {
    const companies = companiesFile(dir, [
      { name: '건전전자', stockCode: '141414', businessNumber: '123-45-67891' },
    ]);
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload', '--escalate-signals', '2',
    ], { FAKE_PIPELINE_CALLS: callsPath, FAKE_PIPELINE_SIGNALS: 'E1', FAKE_PIPELINE_NO_PNG: '1' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    // 파이프라인은 정확히 1회 — 1차 호출에 --agent none 이 강제된다
    const calls = readFileSync(callsPath, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.equal(calls.length, 1);
    const agentIdx = calls[0].indexOf('--agent');
    assert.ok(agentIdx !== -1 && calls[0][agentIdx + 1] === 'none', `1차는 --agent none: ${calls[0]}`);
    assert.doesNotMatch(run.stdout, /승격 재실행/);
    assert.match(run.stdout, /LLM 승격: 0건/);
    // 스탬프 docx 는 1차(기업 폴더) 산출물
    const stamped = readFileSync(join(ROOT, 'outputs', 'batch', PERIOD, '건전전자', `건전전자-CEO-브리핑-${PERIOD}.docx`), 'utf8');
    assert.match(stamped, /건전전자$/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — 재무 신호 임계 이상은 LLM 승격 재실행하고 승격 산출물을 스탬프·보고한다', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['위험전자'];
  const callsPath = join(dir, 'calls.jsonl');
  try {
    const companies = companiesFile(dir, [
      { name: '위험전자', stockCode: '151515', businessNumber: '123-45-67891' },
    ]);
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload', '--escalate-signals', '2',
    ], { FAKE_PIPELINE_CALLS: callsPath, FAKE_PIPELINE_SIGNALS: 'E1,E4,E5', FAKE_PIPELINE_NO_PNG: '1' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    // E5 는 확인 신호라 제외 — 재무 신호는 E1·E4 의 2종으로 임계 도달
    assert.match(run.stdout, /재무 신호 2종 ≥ 2 — LLM 브리핑으로 승격 재실행/);
    assert.match(run.stdout, /위험전자 \(151515\): PASS \(LLM 승격\)/);
    assert.match(run.stdout, /LLM 승격: 1건/);
    // 2회 호출: 1차는 --agent none, 승격 재실행은 --agent 없음(자동 감지)
    const calls = readFileSync(callsPath, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.equal(calls.length, 2);
    assert.ok(calls[0].includes('none'), `1차는 --agent none: ${calls[0]}`);
    assert.ok(!calls[1].includes('--agent'), `승격은 --agent 미지정: ${calls[1]}`);
    // 스탬프 docx 는 escalated 산출물
    const stamped = readFileSync(join(ROOT, 'outputs', 'batch', PERIOD, '위험전자', `위험전자-CEO-브리핑-${PERIOD}.docx`), 'utf8');
    assert.match(stamped, /escalated$/);
    assert.ok(existsSync(join(ROOT, 'outputs', 'batch', PERIOD, '위험전자', 'pipeline-escalated.log')));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — 승격 재실행이 실패하면 이미 PASS 한 1차 로컬 브리핑으로 폴백한다', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['폴백전자'];
  try {
    const companies = companiesFile(dir, [
      { name: '폴백전자', stockCode: '161616', businessNumber: '123-45-67891' },
    ]);
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload', '--escalate-signals', '2',
    ], { FAKE_PIPELINE_SIGNALS: 'E1,E4', FAKE_PIPELINE_FAIL_ESCALATED: '1', FAKE_PIPELINE_NO_PNG: '1' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /승격 실패\(exit 1\) — 1차 로컬 브리핑 유지/);
    assert.match(run.stdout, /폴백전자 \(161616\): PASS \(승격 실패→로컬 유지\)/);
    assert.match(run.stdout, /LLM 승격: 0건/);
    // 스탬프 docx 는 1차(기업 폴더) 산출물 — escalated 가 아니다
    const stamped = readFileSync(join(ROOT, 'outputs', 'batch', PERIOD, '폴백전자', `폴백전자-CEO-브리핑-${PERIOD}.docx`), 'utf8');
    assert.match(stamped, /폴백전자$/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});

test('배치 — --concurrency 2 는 여러 기업을 동시에 처리하고 모두 완주한다', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'batch-'));
  const names = ['동시A', '동시B', '동시C'];
  try {
    const companies = companiesFile(dir, names.map((name, i) => ({
      name, stockCode: `9090${i}0`, businessNumber: '123-45-67891',
    })));
    const run = runNode([
      BATCH_CLI, '--companies', companies, '--period', PERIOD,
      '--pipeline-script', FAKE_PIPELINE, '--no-upload', '--concurrency', '2',
    ], { FAKE_PIPELINE_NO_PNG: '1' });

    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /동시 2/);
    assert.match(run.stdout, /3개사 중 성공 3 \/ 실패 0/);
    for (const name of names) {
      assert.ok(existsSync(join(ROOT, 'outputs', 'batch', PERIOD, name, `${name}-CEO-브리핑-${PERIOD}.docx`)));
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
    cleanupBatchDirs(names);
  }
});
