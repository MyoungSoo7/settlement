#!/usr/bin/env node
/**
 * 브리핑 유니버스 빌더 CLI — 코스피·코스닥 상장사 전체를 DART 로 보강해
 * 분기 브리핑 배치가 소비할 briefing-companies.json 을 만든다.
 *
 *   node src/bin/build-briefing-universe.mjs \
 *     [--out src/data/briefing-companies-universe.json] \
 *     [--markets KOSPI,KOSDAQ] [--limit N] [--only <이름|종목코드>] \
 *     [--delay-ms 250] [--refresh] [--dry-run]
 *
 * 전제: DART_API_KEY (상장사마다 기업개황 1콜로 사업자번호·시장구분 보강).
 *   - 키가 없으면 무엇이 왜 필요한지 안내하고 exit 1 (부분 산출 없음 — 배치는 완전한 항목만 소비).
 *   - --dry-run 은 보강 없이 대상 상장사 수만 보고(캐시만 읽어 키 불필요).
 * 산출: { generatedAt, markets, total, count, companies:[...], skipped:[...] }
 *   companies 항목 = { name, stockCode, businessNumber, corpCode, market, representativeName?, openingDate? }
 */
import { writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadCorpCodes } from '../dart/corp-codes.mjs';
import { company as dartCompany, API_KEY as DART_KEY } from '../dart/client.mjs';
import { buildUniverse } from '../dart/universe.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const argv = process.argv.slice(2);
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};
const has = (name) => argv.includes(name);

if (has('--help') || has('-h')) {
  console.log(`브리핑 유니버스 빌더 — 상장사 전체를 DART 로 보강해 배치 목록 생성

Usage:
  node src/bin/build-briefing-universe.mjs \\
    [--out <경로, 기본 src/data/briefing-companies-universe.json>] \\
    [--markets KOSPI,KOSDAQ] [--limit N] [--only <이름|종목코드>] \\
    [--delay-ms 250] [--refresh] [--dry-run]

전제: DART_API_KEY (상장사마다 기업개황 1콜). --dry-run 은 캐시만 읽어 키 불필요.`);
  process.exit(0);
}

const outPath = resolve(ROOT, flag('--out') ?? join('src', 'data', 'briefing-companies-universe.json'));
const markets = (flag('--markets') ?? 'KOSPI,KOSDAQ').split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
const limit = flag('--limit') !== undefined ? Number(flag('--limit')) : undefined;
if (limit !== undefined && (!Number.isInteger(limit) || limit <= 0)) {
  console.error(`--limit 은 양의 정수여야 합니다: "${flag('--limit')}"`);
  process.exit(1);
}
const only = flag('--only');
const delayMs = flag('--delay-ms') !== undefined ? Number(flag('--delay-ms')) : 250;
const refresh = has('--refresh');
const dryRun = has('--dry-run');

async function loadListed() {
  const data = await loadCorpCodes({ refresh });
  if (!only) return data;
  const kw = String(only).trim();
  const companies = data.companies.filter((c) => c.name === kw || c.stockCode === kw || c.corpCode === kw);
  return { ...data, companies };
}

if (dryRun) {
  const { companies } = await loadListed();
  console.log(`[dry-run] 보강 없이 대상 상장사 ${companies.length}개사 (markets=${markets.join(',')} 필터는 보강 후 적용).`);
  console.log('  실제 목록 생성은 DART_API_KEY 설정 후 --dry-run 없이 실행하세요.');
  process.exit(0);
}

if (!DART_KEY) {
  console.error('DART_API_KEY 가 없습니다 — 상장사 사업자번호·시장구분 보강에 필요합니다.');
  console.error('  발급: https://opendart.fss.or.kr  →  env 또는 상위 .env 에 DART_API_KEY= 설정');
  console.error('  키 없이 대상 규모만 보려면: --dry-run');
  process.exit(1);
}

const started = Date.now();
let lastLog = 0;
const result = await buildUniverse(
  { loadCorpCodes: loadListed, fetchCompany: (corpCode) => dartCompany(corpCode) },
  {
    markets,
    limit,
    delayMs,
    onProgress: ({ index, total, name }) => {
      const now = Date.now();
      if (now - lastLog > 2000 || index === 0) {
        console.log(`  [${index + 1}/${total}] ${name} …`);
        lastLog = now;
      }
    },
  },
);

const payload = {
  generatedAt: new Date().toISOString(),
  markets: result.markets,
  total: result.total,
  count: result.count,
  companies: result.companies,
  skipped: result.skipped,
};
writeFileSync(outPath, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');

const elapsed = ((Date.now() - started) / 1000).toFixed(1);
console.log(`\n유니버스 생성 완료 — ${result.count}개사 (제외 ${result.skipped.length}건), ${elapsed}s`);
console.log(`  → ${outPath}`);
console.log(`  배치 실행: node src/bin/quarterly-briefing-batch.mjs --companies ${flag('--out') ?? 'src/data/briefing-companies-universe.json'} --register http://localhost:8090`);
