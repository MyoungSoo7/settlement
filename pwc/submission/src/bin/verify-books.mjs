#!/usr/bin/env node
/**
 * 불변식 게이트 (verify-books) — LLM 추론 전에 기계적으로 확정 가능한 정합성을 먼저 검증한다.
 *
 * 철학 (doc/회계.md): "불변식(시산표 균형, 대사 일치)으로 확정할 수 있는 것을 먼저
 * 기계적으로 확정하고, 그 위에서만 AI 추론이 의미를 갖는다."
 * 이 게이트가 FAIL 이면 에이전트는 리스크 추론에 진입하지 않고,
 * 실패 항목 자체를 "데이터 품질 리스크"로 보고해야 한다.
 *
 * 검증 로직은 common/books.mjs(내부 불변식 7종) + common/crosscheck.mjs(INV-8 외부 대사)에 있다.
 * 특정 분기·특정 회사에 대한 하드코딩 없음 — 어떤 회사 데이터 폴더에도 그대로 적용된다.
 *
 * 사용:
 *   node bin/verify-books.mjs                          # 기본: 동봉 샘플 데이터
 *   node bin/verify-books.mjs --data-dir <회사데이터폴더>  # 임의 회사 데이터
 *   node bin/verify-books.mjs --json                   # 기계가 읽는 JSON
 *   (env VERIFY_BOOKS_DATA_DIR 로도 데이터 폴더 지정 가능 — --data-dir 이 우선)
 *
 * INV-8 상장사 외부 대사 (선택 — 코스피·코스닥 등 DART 공시 법인일 때):
 *   node bin/verify-books.mjs --data-dir <폴더> --dart-corp-code 00126380 \
 *     [--dart-year 2025] [--dart-fs-div OFS|CFS] [--dart-unit-scale 1000000] [--dart-tolerance-pct 1]
 *   또는 데이터 폴더의 analysis-config.json 에 "crosscheck" 섹션으로 상시 설정 (플래그가 우선).
 *   내부 불변식(INV-1~7)이 PASS 인 경우에만 실행되며, DART_API_KEY 가 필요하다.
 */
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadBooks, runInvariants, resolveDataDir, BooksLoadError } from '../common/books.mjs';
import { loadCrosscheckConfig, runDartCrosscheck } from '../common/crosscheck.mjs';

const DEFAULT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'sample');
const argv = process.argv.slice(2);
const DATA_DIR = resolveDataDir(argv, DEFAULT_DIR);
const asJson = argv.includes('--json');
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};

let summary;
let books = null;
try {
  books = loadBooks(DATA_DIR);
  summary = runInvariants(books);
} catch (error) {
  if (!(error instanceof BooksLoadError)) throw error;
  summary = { gate: 'FAIL', loadError: error.message, checks: [] };
}

// INV-8 외부 대사 — corp_code 가 설정됐고 내부 불변식이 PASS 일 때만 (훼손 장부 위 대사는 무의미).
if (books && summary.gate === 'PASS') {
  const cc = loadCrosscheckConfig(DATA_DIR);
  if (flag('--dart-corp-code')) cc.corpCode = flag('--dart-corp-code');
  if (flag('--dart-year')) cc.year = flag('--dart-year');
  if (flag('--dart-fs-div')) cc.fsDiv = flag('--dart-fs-div');
  if (flag('--dart-unit-scale')) cc.unitScale = Number(flag('--dart-unit-scale'));
  if (flag('--dart-tolerance-pct')) cc.tolerancePct = Number(flag('--dart-tolerance-pct'));
  if (cc.corpCode) {
    const inv8 = await runDartCrosscheck(books, cc);
    summary.checks.push(inv8);
    if (!inv8.pass) summary.gate = 'FAIL';
  }
}

if (asJson) {
  console.log(JSON.stringify({ dataDir: DATA_DIR, ...summary }, null, 2));
} else {
  console.log(`=== 불변식 게이트 (verify-books) — ${DATA_DIR} ===`);
  if (summary.loadError) {
    console.log(`FAIL  LOAD ${summary.loadError}`);
  }
  for (const r of summary.checks) {
    const badge = r.skipped ? 'skip' : r.pass ? '  ok' : 'FAIL';
    console.log(`${badge}  ${r.id} ${r.name}${r.detail ? ` — ${r.detail}` : ''}`);
  }
  const failCount = summary.checks.filter((r) => !r.pass).length + (summary.loadError ? 1 : 0);
  console.log(summary.gate === 'PASS'
    ? '\nGATE PASS — 기계적 정합성 확정. 추론 단계 진입 가능.'
    : `\nGATE FAIL — ${failCount}건 위반. 리스크 추론에 진입하지 말고 데이터 품질 리스크로 보고할 것.`);
}

if (summary.gate !== 'PASS') process.exitCode = 1;
