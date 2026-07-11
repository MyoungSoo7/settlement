#!/usr/bin/env node
/**
 * 분기 브리핑 배치 — 대상 기업 목록을 순회하며 CEO 컨설팅 파이프라인을 완주시키고,
 * EVAL PASS 한 브리핑만 기간 스탬프 파일명으로 company-service 문서함에 업로드한다.
 *
 *   node src/bin/quarterly-briefing-batch.mjs [--period 2026Q2] [--with-news --with-market]
 *     [--companies src/data/briefing-companies.json] [--only <기업명|종목코드>]
 *     [--upload http://localhost:8090 | --no-upload] [--judge] [--agent none|"<cmd>"]
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
import { spawnSync } from 'node:child_process';

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
    [--with-news] [--with-market] [--judge] [--agent none|"<cmd>"]

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
const only = flag('--only');
const targets = only
  ? companies.filter((c) => c.name === only || c.stockCode === only)
  : companies;
if (!Array.isArray(targets) || targets.length === 0) {
  fail(only ? `--only "${only}" 에 해당하는 기업이 목록에 없습니다` : '기업 목록이 비어 있습니다');
}

const uploadBase = has('--no-upload') ? null : (flag('--upload') ?? 'http://localhost:8090').replace(/\/$/, '');
const apiKey = process.env.COMPANY_INTERNAL_API_KEY ?? '';
const pipelineScript = resolve(ROOT, flag('--pipeline-script') ?? join('src', 'bin', 'ceo-consulting-pipeline.mjs'));
const passthrough = [];
for (const name of ['--with-news', '--with-market', '--judge']) {
  if (has(name)) passthrough.push(name);
}
if (flag('--agent') !== undefined) passthrough.push('--agent', flag('--agent'));

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

console.log(`분기 브리핑 배치 — period=${period}, 대상 ${targets.length}개사, 업로드=${uploadBase ?? '(생략)'}\n`);

const results = [];
for (const company of targets) {
  const { name, stockCode, businessNumber, corpCode } = company;
  const result = { name, stockCode, eval: 'FAIL', uploaded: [], error: null };
  results.push(result);
  console.log(`━━ ${name} (${stockCode}) ━━`);
  if (!name || !stockCode || !businessNumber) {
    result.error = '목록 항목에 name/stockCode/businessNumber 가 모두 필요합니다';
    console.error(`  건너뜀: ${result.error}`);
    continue;
  }

  const outDir = join(ROOT, 'outputs', 'batch', period, name);
  mkdirSync(outDir, { recursive: true });
  const run = spawnSync(process.execPath, [
    pipelineScript,
    '--company', name,
    '--business-number', businessNumber,
    // corpCode 명시 — 동명이인(예: 삼성물산 구/현 법인) 시 이름 검색이 상장폐지 법인을
    // 잡는 것을 막는다. 목록에 없으면 파이프라인이 이름으로 검색한다.
    ...(corpCode ? ['--corp-code', corpCode] : []),
    '--out-dir', outDir,
    ...passthrough,
  ], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: PIPELINE_TIMEOUT_MS });

  writeFileSync(join(outDir, 'pipeline.log'), `${run.stdout ?? ''}\n${run.stderr ?? ''}`, 'utf8');

  if (run.status !== 0) {
    result.error = run.signal
      ? `파이프라인 타임아웃/시그널(${run.signal})`
      : `파이프라인 exit ${run.status} (EVAL FAIL 포함 — pipeline.log 확인)`;
    console.error(`  실패: ${result.error}`);
    continue;
  }
  result.eval = 'PASS';
  console.log('  EVAL PASS — 브리핑 채점 통과');

  // 기간 스탬프 사본 — 분기별 이력 보존 (같은 분기 재실행은 문서함에서 교체)
  const artifacts = [];
  const docxSrc = join(outDir, 'briefing.docx');
  if (!existsSync(docxSrc)) {
    result.error = 'EVAL PASS 인데 briefing.docx 가 없습니다 (pipeline.log 확인)';
    result.eval = 'FAIL';
    console.error(`  실패: ${result.error}`);
    continue;
  }
  const stampedDocx = join(outDir, `${name}-CEO-브리핑-${period}.docx`);
  copyFileSync(docxSrc, stampedDocx);
  artifacts.push({ path: stampedDocx, title: `${name} CEO 리스크 브리핑 (${period})` });

  const pngSrc = join(outDir, 'executive-summary.png');
  if (existsSync(pngSrc)) {
    const stampedPng = join(outDir, `${name}-경영진-스냅샷-${period}.png`);
    copyFileSync(pngSrc, stampedPng);
    artifacts.push({ path: stampedPng, title: `${name} 경영진 스냅샷 (${period})` });
  }

  if (!uploadBase) {
    console.log(`  업로드 생략 (--no-upload) — 산출물: ${artifacts.map((a) => basename(a.path)).join(', ')}`);
    continue;
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
}

console.log('\n━━ 배치 요약 ━━');
for (const r of results) {
  const status = r.error ? `실패 — ${r.error}` : `${r.eval}, 업로드 ${r.uploaded.length}건`;
  console.log(`  ${r.name} (${r.stockCode}): ${status}`);
}
const failures = results.filter((r) => r.error).length;
console.log(`\n${results.length}개사 중 성공 ${results.length - failures} / 실패 ${failures}`);
process.exit(failures > 0 ? 1 : 0);
