import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
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
        received.push({
          url: req.url,
          apiKey: req.headers['x-internal-api-key'] ?? null,
          body: Buffer.concat(chunks).toString('utf8'),
        });
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
