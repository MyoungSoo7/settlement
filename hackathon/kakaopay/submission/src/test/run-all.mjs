#!/usr/bin/env node
/**
 * 전체 스모크 러너 — 4개 테스트를 순차 실행하고 종합 결과를 낸다.
 * 실행: node src/test/run-all.mjs
 * (키가 있으면 라이브 검증 포함, 없으면 프로토콜 검증만 — 어느 쪽이든 GREEN 이어야 함)
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const suites = [
  'common-utils-test', 'backtest-core-test', 'trade-plan-test',
  'dart-smoke', 'ecos-smoke', 'news-smoke', 'price-smoke',
];

let failures = 0;
for (const suite of suites) {
  console.log(`\n=== ${suite} ===`);
  const result = spawnSync(process.execPath, [join(here, `${suite}.mjs`)], {
    stdio: 'inherit',
    timeout: 60_000,
  });
  if (result.status !== 0) failures += 1;
}

console.log(failures === 0 ? '\n>>> ALL SUITES GREEN' : `\n>>> ${failures} SUITE(S) FAILED`);
if (failures > 0) process.exitCode = 1;
