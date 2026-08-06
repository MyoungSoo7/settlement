#!/usr/bin/env node
/**
 * 전체 테스트 러너 — 결정론 스위트 + 단위(unit, 네트워크 0) + 스모크(라이브)를
 * 순차 실행하고 종합 결과를 낸다.
 * 실행: node src/test/run-all.mjs
 * (키가 있으면 스모크가 라이브 검증 포함, 없으면 프로토콜 검증만 — 어느 쪽이든 GREEN 이어야 함.
 *  unit/ 스위트는 fetch 스텁 기반이라 키·네트워크 없이 항상 전체 검증된다.)
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const suites = [
  'common-utils-test', 'backtest-core-test', 'trade-plan-test', 'sector-report-test',
  'unit/mcp-servers.test', 'unit/price-client.test', 'unit/invest-cycle.test',
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
