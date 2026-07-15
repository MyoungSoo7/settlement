#!/usr/bin/env node
/**
 * 전체 스모크 러너 — 데이터 정합 + MCP 4종을 순차 실행하고 종합 결과를 낸다.
 * 실행: node src/test/run-all.mjs
 * (키가 있으면 라이브 검증 포함, 없으면 프로토콜 검증만 — 어느 쪽이든 GREEN 이어야 함)
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const suites = ['closet-data-test', 'shop-smoke', 'news-smoke', 'weather-smoke', 'trend-smoke'];

let failures = 0;
for (const suite of suites) {
  console.log(`\n=== ${suite} ===`);
  const result = spawnSync(process.execPath, [join(here, `${suite}.mjs`)], {
    stdio: 'inherit',
    timeout: 120_000,
  });
  if (result.status !== 0) failures += 1;
}

console.log(failures === 0 ? '\n>>> ALL SUITES GREEN' : `\n>>> ${failures} SUITE(S) FAILED`);
if (failures > 0) process.exitCode = 1;
