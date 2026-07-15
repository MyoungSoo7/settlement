#!/usr/bin/env node
/**
 * 분기 브리핑 배치 — 대상 기업 목록을 순회하며 CEO 컨설팅 파이프라인을 완주시키고,
 * EVAL PASS 한 브리핑만 기간 스탬프 파일명으로 company-service 문서함에 업로드한다.
 *
 *   node src/bin/quarterly-briefing-batch.mjs [--period 2026Q2] [--with-news --with-market]
 *     [--companies src/data/briefing-companies.json] [--only <기업명|종목코드>]
 *     [--upload http://localhost:8090 | --no-upload] [--judge] [--agent none|"<cmd>"]
 *     [--escalate-signals N]   # 2단 토큰 절약: 1차 전량 로컬 브리핑 → 재무 신호 N종+ 기업만 LLM 승격
 *
 * 설계 원칙:
 * - 기간 스탬프 파일명(`<기업명>-CEO-브리핑-<period>.docx`) — 분기별 이력이 문서함에 쌓이고,
 *   같은 분기 재실행은 (stockCode, fileName) 교체 시맨틱으로 멱등이다.
 * - EVAL PASS(파이프라인 exit 0)만 업로드 — 채점 게이트가 곧 업로드 게이트.
 * - 기업 하나가 실패해도 다음 기업으로 계속 진행하고, 마지막에 요약 + exit 1 로 알린다.
 * - 업로드 대상은 company-service 에 등록된 기업(stockCode)이어야 한다 — 미등록이면 404 로 보고됨.
 * - 시크릿은 COMPANY_INTERNAL_API_KEY env (미설정 시 헤더 생략 — 로컬 무게이팅과 정합).
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const argv = process.argv.slice(2);

const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};
const has = (name) => argv.includes(name);

function fail(message) {
  console.error(message);
  process.exit(1);
}

if (has('--help') || has('-h')) {
  console.log(`분기 브리핑 배치 — 기업 목록 순회 → 파이프라인 → EVAL PASS 만 문서함 업로드

Usage:
  node src/bin/quarterly-briefing-batch.mjs \\
    [--period <라벨, 기본=직전 분기 예: 2026Q2>] \\
    [--companies <목록 JSON, 기본 src/data/briefing-companies.json>] \\
    [--only <기업명|종목코드>] \\
    [--upload <base URL, 기본 http://localhost:8090> | --no-upload] \\
    [--register <company-service base URL>]  # 업로드 전 기업 마스터 일괄 등록(미등록=업로드 404 방지) \\
    [--resume]                               # 기간 스탬프 docx 가 이미 있는 기업은 건너뜀(대량 배치 재개) \\
    [--concurrency <N, 기본 1>] [--delay-ms <ms, 기본 0>]  # 동시 실행 수 · 착수 간 지연(레이트리밋) \\
    [--with-news] [--with-market] [--judge] [--agent none|"<cmd>"] \\
    [--escalate-signals <N>]  # 1차 전량 --agent none(LLM 0), 재무 신호(E1~E4·E8) N종+ 발화 기업만 LLM 승격 재실행

목록 JSON 항목: { "name": "삼성전자", "stockCode": "005930", "businessNumber": "124-81-00998" }
산출물: outputs/batch/<period>/<기업명>/ (파이프라인 전체 + 기간 스탬프 사본 + pipeline.log)`);
  process.exit(0);
}

/** 직전 분기 라벨 — 분기보고서 마감(분기말+45일) 직후 실행을 전제로 "직전 분기"가 기본값. */
function derivePeriod(now) {
  const quarterIndex = Math.floor(now.getMonth() / 3); // 0~3 = 현재 분기
  const year = quarterIndex === 0 ? now.getFullYear() - 1 : now.getFullYear();
  const quarter = quarterIndex === 0 ? 4 : quarterIndex;
  return `${year}Q${quarter}`;
}

// BATCH_TODAY: 테스트가 기간 파생을 결정론으로 만들기 위한 주입 지점
const today = process.env.BATCH_TODAY ? new Date(process.env.BATCH_TODAY) : new Date();
const period = flag('--period') ?? derivePeriod(today);
if (!/^\d{4}Q[1-4]$/.test(period)) fail(`--period 형식은 YYYYQn 입니다: "${period}"`);

// --escalate-signals: 2단 토큰 절약 모드 — 1차는 전 기업을 --agent none(결정론 로컬 브리핑, LLM 0)으로
// 완주하고, 재무 위험 신호가 N종 이상 발화한 기업만 에이전트(claude/codex 자동 감지)로 승격 재실행한다.
// 탐지(게이트+신호 파생)는 두 경우 모두 매 분기 최신 공시로 전량 수행되므로 커버리지 손실이 없다.
// 인자 검증은 --print-period 조기 종료보다 앞 — 잘못된 조합은 어떤 모드에서든 즉시 실패한다.
const escalateSignals = flag('--escalate-signals') !== undefined ? Number(flag('--escalate-signals')) : null;
if (escalateSignals !== null && (!Number.isInteger(escalateSignals) || escalateSignals < 1)) {
  fail(`--escalate-signals 는 1 이상의 정수여야 합니다: "${flag('--escalate-signals')}"`);
}
if (escalateSignals !== null && flag('--agent') !== undefined) {
  fail('--escalate-signals 는 1차를 --agent none 으로, 승격 재실행을 에이전트 자동 감지로 강제합니다 — --agent 와 함께 쓸 수 없습니다');
}
if (has('--print-period')) {
  console.log(period);
  process.exit(0);
}

const companiesPath = resolve(ROOT, flag('--companies') ?? join('src', 'data', 'briefing-companies.json'));
let companies;
try {
  companies = JSON.parse(readFileSync(companiesPath, 'utf8'));
} catch (error) {
  fail(`기업 목록을 읽지 못했습니다 (${companiesPath}): ${error.message}`);
}
// 두 형태 수용: 큐레이션 파일은 최상위 배열, 유니버스 빌더 산출물은 { companies:[...] } 객체.
if (companies && !Array.isArray(companies) && Array.isArray(companies.companies)) {
  companies = companies.companies;
}
const only = flag('--only');
const targets = only
  ? companies.filter((c) => c.name === only || c.stockCode === only)
  : companies;
if (!Array.isArray(targets) || targets.length === 0) {
  fail(only ? `--only "${only}" 에 해당하는 기업이 목록에 없습니다` : '기업 목록이 비어 있습니다');
}

const uploadBase = has('--no-upload') ? null : (flag('--upload') ?? 'http://localhost:8090').replace(/\/$/, '');
const apiKey = process.env.COMPANY_INTERNAL_API_KEY ?? '';
// --register: 업로드 전에 대상 기업을 company-service 기업 마스터에 일괄 등록(미등록이면 업로드 404).
const registerBase = flag('--register') ? flag('--register').replace(/\/$/, '') : null;
// --resume: 이미 기간 스탬프 docx 가 있는 기업은 건너뛴다(중단된 대량 배치 재개).
const resume = has('--resume');
// --delay-ms: 기업 착수 간 지연(외부 API 레이트리밋). --concurrency: 동시에 도는 파이프라인 수(기본 1=직렬).
const delayMs = flag('--delay-ms') !== undefined ? Number(flag('--delay-ms')) : 0;
if (!Number.isFinite(delayMs) || delayMs < 0) fail(`--delay-ms 는 0 이상의 수여야 합니다: "${flag('--delay-ms')}"`);
const concurrency = flag('--concurrency') !== undefined ? Number(flag('--concurrency')) : 1;
if (!Number.isInteger(concurrency) || concurrency < 1) fail(`--concurrency 는 1 이상의 정수여야 합니다: "${flag('--concurrency')}"`);
const pipelineScript = resolve(ROOT, flag('--pipeline-script') ?? join('src', 'bin', 'ceo-consulting-pipeline.mjs'));
const passthrough = [];
for (const name of ['--with-news', '--with-market', '--judge']) {
  if (has(name)) passthrough.push(name);
}
if (flag('--agent') !== undefined) passthrough.push('--agent', flag('--agent'));

// 승격 판정 대상은 재무 위험 신호(E1~E4·E8)만 — E5(공시 행간)는 설계상 확인 신호(건전 코호트
// 발화율 86.7%)라 포함하면 사실상 전량 승격이 되고, E6·E7(시장)·뉴스·문서 축은 옵션 축이라 제외.
// 권장 기준 2 의 근거: 무작위 50개사 실측(2026-07-15)에서 건전 기업 동시 발화 최대 2종,
// 태영건설 워크아웃 백테스트(FY2022)는 3종 동시 발화 — 2 는 위험 후보를 놓치지 않는 하한이다.
const FINANCIAL_SIGNAL_IDS = new Set(['E1', 'E2', 'E3', 'E4', 'E8']);
function countFinancialSignals(outDir) {
  const packetPath = join(outDir, 'diagnostic-packet.json');
  if (!existsSync(packetPath)) return null;
  try {
    const packet = JSON.parse(readFileSync(packetPath, 'utf8'));
    const signals = Array.isArray(packet.signals) ? packet.signals : [];
    return signals.filter((s) => s?.present === true && FINANCIAL_SIGNAL_IDS.has(s?.id)).length;
  } catch {
    return null;
  }
}

const PIPELINE_TIMEOUT_MS = 900_000; // LLM 브리핑 포함 기업당 상한 15분

const UPLOAD_ATTEMPTS = 3; // 파이프라인이 수 분 걸려 keep-alive 소켓이 죽은 뒤 첫 업로드가 reset 될 수 있다

async function uploadDocument({ stockCode, title, filePath }) {
  let lastError;
  for (let attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt += 1) {
    try {
      const form = new FormData();
      form.set('stockCode', stockCode);
      form.set('title', title);
      form.set('file', new File([readFileSync(filePath)], basename(filePath)));
      const res = await fetch(`${uploadBase}/admin/company/documents`, {
        method: 'POST',
        headers: apiKey ? { 'X-Internal-Api-Key': apiKey } : {},
        body: form,
      });
      if (!res.ok) {
        const detail = (await res.text().catch(() => '')).slice(0, 300);
        throw new Error(`HTTP ${res.status} ${detail}`);
      }
      return res.json();
    } catch (error) {
      lastError = error;
      if (attempt < UPLOAD_ATTEMPTS) {
        console.log(`  업로드 재시도 ${attempt + 1}/${UPLOAD_ATTEMPTS} (${error.message})`);
        await new Promise((r) => setTimeout(r, 1_000 * attempt));
      }
    }
  }
  throw lastError;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 파이프라인을 비동기 spawn 으로 실행 — 병렬 풀에서 이벤트 루프를 막지 않는다(spawnSync 대체). */
function runPipelineAsync(args) {
  return new Promise((resolveRun) => {
    const child = spawn(process.execPath, args, {});
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    const timer = setTimeout(() => { timedOut = true; child.kill('SIGTERM'); }, PIPELINE_TIMEOUT_MS);
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('error', (e) => {
      clearTimeout(timer);
      resolveRun({ status: 1, signal: null, stdout, stderr: `${stderr}${e.message}` });
    });
    child.on('close', (status, signal) => {
      clearTimeout(timer);
      resolveRun({ status, signal: timedOut ? 'SIGTERM' : signal, stdout, stderr });
    });
  });
}

/** 업로드 전에 대상 기업을 기업 마스터에 일괄 등록 — 실패해도 배치는 계속(개별 업로드가 404 로 보고). */
async function registerCompanies(base, companies) {
  const payload = {
    companies: companies
      .filter((c) => c.name && c.stockCode)
      .map((c) => ({ stockCode: c.stockCode, corpCode: c.corpCode ?? null, name: c.name, market: c.market ?? null })),
  };
  try {
    const res = await fetch(`${base}/admin/company/companies`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(apiKey ? { 'X-Internal-Api-Key': apiKey } : {}) },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      const detail = (await res.text().catch(() => '')).slice(0, 200);
      console.error(`기업 마스터 등록 실패 HTTP ${res.status} ${detail} — 미등록 기업은 업로드가 404`);
      return;
    }
    const r = await res.json();
    console.log(`기업 마스터 등록: 요청 ${r.received} · 신규 ${r.registered} · 갱신 ${r.updated} · 스킵 ${r.skipped}`);
  } catch (error) {
    console.error(`기업 마스터 등록 오류: ${error.message} — 미등록 기업은 업로드가 404`);
  }
}

/** 기업 1개 완주 — 검증 → (resume 스킵) → 파이프라인 → 스탬프 사본 → 업로드. 결과 객체 반환. */
async function processCompany(company) {
  const { name, stockCode, businessNumber, corpCode } = company;
  const result = { name, stockCode, eval: 'FAIL', uploaded: [], error: null };
  console.log(`━━ ${name} (${stockCode}) ━━`);
  if (!name || !stockCode || !businessNumber) {
    result.error = '목록 항목에 name/stockCode/businessNumber 가 모두 필요합니다';
    console.error(`  건너뜀: ${result.error}`);
    return result;
  }

  const outDir = join(ROOT, 'outputs', 'batch', period, name);
  const stampedDocx = join(outDir, `${name}-CEO-브리핑-${period}.docx`);
  if (resume && existsSync(stampedDocx)) {
    result.eval = 'SKIP';
    console.log('  이미 완료 — 건너뜀 (--resume)');
    return result;
  }
  mkdirSync(outDir, { recursive: true });
  const baseArgs = [
    pipelineScript,
    '--company', name,
    '--business-number', businessNumber,
    // corpCode 명시 — 동명이인(예: 삼성물산 구/현 법인) 시 이름 검색이 상장폐지 법인을
    // 잡는 것을 막는다. 목록에 없으면 파이프라인이 이름으로 검색한다.
    ...(corpCode ? ['--corp-code', corpCode] : []),
  ];
  const run = await runPipelineAsync([
    ...baseArgs,
    '--out-dir', outDir,
    ...passthrough,
    // 승격 모드의 1차는 로컬 브리핑(LLM 0) — 승격 판정용 진단 패킷과 기본 산출물을 만든다.
    ...(escalateSignals !== null ? ['--agent', 'none'] : []),
  ]);

  writeFileSync(join(outDir, 'pipeline.log'), `${run.stdout ?? ''}\n${run.stderr ?? ''}`, 'utf8');

  if (run.status !== 0) {
    result.error = run.signal
      ? `파이프라인 타임아웃/시그널(${run.signal})`
      : `파이프라인 exit ${run.status} (EVAL FAIL 포함 — pipeline.log 확인)`;
    console.error(`  실패: ${result.error}`);
    return result;
  }
  result.eval = 'PASS';
  console.log('  EVAL PASS — 브리핑 채점 통과');

  // 승격 재실행 — 1차 산출물은 그대로 두고 escalated/ 하위 폴더에 따로 생성해, 승격이
  // EVAL FAIL 해도 이미 PASS 한 로컬 브리핑으로 안전하게 폴백한다(덮어쓰기 없음).
  let artifactDir = outDir;
  if (escalateSignals !== null) {
    const fired = countFinancialSignals(outDir);
    if (fired === null) {
      console.log('  승격 판정 불가(diagnostic-packet.json 없음/파싱 실패) — 로컬 브리핑 유지');
    } else if (fired >= escalateSignals) {
      console.log(`  재무 신호 ${fired}종 ≥ ${escalateSignals} — LLM 브리핑으로 승격 재실행`);
      const escalatedDir = join(outDir, 'escalated');
      mkdirSync(escalatedDir, { recursive: true });
      const escalatedRun = await runPipelineAsync([...baseArgs, '--out-dir', escalatedDir, ...passthrough]);
      writeFileSync(join(outDir, 'pipeline-escalated.log'), `${escalatedRun.stdout ?? ''}\n${escalatedRun.stderr ?? ''}`, 'utf8');
      if (escalatedRun.status === 0 && existsSync(join(escalatedDir, 'briefing.docx'))) {
        artifactDir = escalatedDir;
        result.escalated = 'LLM';
        console.log('  승격 성공 — LLM 브리핑으로 교체');
      } else {
        result.escalated = 'FALLBACK';
        console.log(`  승격 실패(exit ${escalatedRun.status ?? escalatedRun.signal}) — 1차 로컬 브리핑 유지 (pipeline-escalated.log 확인)`);
      }
    }
  }

  // 기간 스탬프 사본 — 분기별 이력 보존 (같은 분기 재실행은 문서함에서 교체)
  const artifacts = [];
  const docxSrc = join(artifactDir, 'briefing.docx');
  if (!existsSync(docxSrc)) {
    result.error = 'EVAL PASS 인데 briefing.docx 가 없습니다 (pipeline.log 확인)';
    result.eval = 'FAIL';
    console.error(`  실패: ${result.error}`);
    return result;
  }
  copyFileSync(docxSrc, stampedDocx);
  artifacts.push({ path: stampedDocx, title: `${name} CEO 리스크 브리핑 (${period})` });

  const pngSrc = join(artifactDir, 'executive-summary.png');
  if (existsSync(pngSrc)) {
    const stampedPng = join(outDir, `${name}-경영진-스냅샷-${period}.png`);
    copyFileSync(pngSrc, stampedPng);
    artifacts.push({ path: stampedPng, title: `${name} 경영진 스냅샷 (${period})` });
  }

  if (!uploadBase) {
    console.log(`  업로드 생략 (--no-upload) — 산출물: ${artifacts.map((a) => basename(a.path)).join(', ')}`);
    return result;
  }
  for (const artifact of artifacts) {
    try {
      const saved = await uploadDocument({ stockCode, title: artifact.title, filePath: artifact.path });
      result.uploaded.push(basename(artifact.path));
      console.log(`  업로드 완료: ${basename(artifact.path)} (id ${saved.id})`);
    } catch (error) {
      result.error = `업로드 실패 ${basename(artifact.path)}: ${error.message}`;
      console.error(`  ${result.error}`);
    }
  }
  return result;
}

console.log(`분기 브리핑 배치 — period=${period}, 대상 ${targets.length}개사, 업로드=${uploadBase ?? '(생략)'}${concurrency > 1 ? `, 동시 ${concurrency}` : ''}\n`);

if (registerBase) await registerCompanies(registerBase, targets);

// 병렬 풀 — 결과는 대상 순서대로 채워 요약 순서를 안정화한다(concurrency 1 이면 기존 직렬과 동일).
const results = new Array(targets.length);
let cursor = 0;
async function worker() {
  while (true) {
    const index = cursor;
    cursor += 1;
    if (index >= targets.length) return;
    if (delayMs && index > 0) await sleep(delayMs);
    results[index] = await processCompany(targets[index]);
  }
}
await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(targets.length, 1)) }, worker));

console.log('\n━━ 배치 요약 ━━');
for (const r of results) {
  let status;
  if (r.error) status = `실패 — ${r.error}`;
  else if (r.eval === 'SKIP') status = '이미 완료 — 건너뜀 (resume)';
  else {
    const escalatedTag = r.escalated === 'LLM' ? ' (LLM 승격)' : r.escalated === 'FALLBACK' ? ' (승격 실패→로컬 유지)' : '';
    status = `${r.eval}${escalatedTag}, 업로드 ${r.uploaded.length}건`;
  }
  console.log(`  ${r.name} (${r.stockCode}): ${status}`);
}
const failures = results.filter((r) => r.error).length;
const skipped = results.filter((r) => r.eval === 'SKIP').length;
console.log(`\n${results.length}개사 중 성공 ${results.length - failures - skipped} / 실패 ${failures}${skipped ? ` / 건너뜀 ${skipped}` : ''}`);
if (escalateSignals !== null) {
  const escalatedCount = results.filter((r) => r?.escalated === 'LLM').length;
  console.log(`LLM 승격: ${escalatedCount}건 (기준: 재무 신호 ${escalateSignals}종 이상 — 나머지는 로컬 브리핑, LLM 토큰 0)`);
}
process.exit(failures > 0 ? 1 : 0);
