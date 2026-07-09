#!/usr/bin/env node
/**
 * 신호 파생 CLI (detect-signals) — 불변식 게이트 통과 후, 데이터에서 리스크 신호를
 * 결정론적으로 파생해 "진단 패킷"으로 출력한다.
 *
 * 역할 분담:
 *   - 이 CLI(기계): 증가율·집중도·재배부 손익 같은 수치를 계산하고 임계값으로 신호를 판정한다.
 *     → 에이전트가 비율을 손으로 암산하다 틀리는 사고를 차단한다.
 *   - 에이전트(LLM): 판정된 신호를 인과 사슬로 엮어 "왜 문제인가"를 CEO 언어로 설명하고,
 *     확신도·판별 테스트·권고 조치를 붙인 브리핑을 작성한다.
 *   - 채점기(briefing-eval): 같은 파생 결과를 정답지로 사용해 브리핑을 채점한다.
 *
 * ABSENT 신호를 브리핑에서 리스크로 단정하면 채점기가 오탐으로 잡는다 — 지어내지 말 것.
 *
 * 사용:
 *   node bin/detect-signals.mjs                          # 동봉 샘플 데이터
 *   node bin/detect-signals.mjs --data-dir <회사데이터폴더>
 *   node bin/detect-signals.mjs --json                   # 기계가 읽는 JSON
 */
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadBooks, runInvariants, resolveDataDir, BooksLoadError } from '../common/books.mjs';
import { deriveSignals } from '../common/signals.mjs';
import { resolveThresholds } from '../common/presets.mjs';

const DEFAULT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'sample');
const argv = process.argv.slice(2);
const DATA_DIR = resolveDataDir(argv, DEFAULT_DIR);
const asJson = argv.includes('--json');

let books;
let gate;
try {
  books = loadBooks(DATA_DIR);
  gate = runInvariants(books);
} catch (error) {
  if (!(error instanceof BooksLoadError)) throw error;
  const msg = `게이트 진입 불가 — ${error.message}`;
  if (asJson) console.log(JSON.stringify({ dataDir: DATA_DIR, gate: 'FAIL', loadError: error.message, signals: [] }, null, 2));
  else console.error(msg);
  process.exitCode = 1;
}

if (gate) {
  if (gate.gate !== 'PASS') {
    // 게이트 실패 위에서 파생한 신호는 신뢰할 수 없다 — 신호 파생을 거부한다.
    if (asJson) {
      console.log(JSON.stringify({ dataDir: DATA_DIR, gate: gate.gate, checks: gate.checks, signals: [] }, null, 2));
    } else {
      console.error(`GATE FAIL — 불변식 위반 상태에서는 신호를 파생하지 않습니다. 먼저 node bin/verify-books.mjs --data-dir "${DATA_DIR}" 로 위반 항목을 확인하세요.`);
    }
    process.exitCode = 1;
  } else {
    const presetIdx = argv.indexOf('--preset');
    const { thresholds, presetUsed } = resolveThresholds({
      dataDir: DATA_DIR,
      preset: presetIdx !== -1 ? argv[presetIdx + 1] : undefined,
      kind: 'internal',
    });
    const signals = deriveSignals(books, thresholds);
    const presentCount = signals.filter((s) => s.present).length;

    if (asJson) {
      // markers(RegExp)는 JSON 직렬화 시 소스 문자열로 노출한다.
      const out = signals.map((s) => ({ ...s, markers: s.markers.map((m) => m.source), categoryPattern: s.categoryPattern.source }));
      console.log(JSON.stringify({ dataDir: DATA_DIR, gate: 'PASS', presetUsed, thresholds, presentCount, signals: out }, null, 2));
    } else {
      console.log(`=== 신호 파생 (detect-signals) — ${DATA_DIR} ===`);
      console.log(`게이트: PASS / 판정 신호: ${presentCount}건${presetUsed ? ` / 프리셋: ${presetUsed}` : ''}\n`);
      for (const s of signals) {
        const badge = !s.evaluable ? 'N/A    ' : s.present ? 'PRESENT' : 'absent ';
        console.log(`[${badge}] ${s.id} ${s.name}${s.note ? ` — ${s.note}` : ''}`);
        for (const [key, value] of Object.entries(s.evidence)) {
          console.log(`          ${key}: ${typeof value === 'object' ? JSON.stringify(value) : value}`);
        }
        if (s.present) {
          console.log(`          확인 포인트: ${s.checkHints.join(' / ')}`);
        }
      }
      console.log(presentCount === 0
        ? '\n판정 신호 없음 — 브리핑은 "이상 없음"을 확인 범위와 함께 보고할 것 (리스크를 지어내면 채점기가 오탐으로 잡음).'
        : `\nPRESENT ${presentCount}건 — 각 신호를 인과 사슬·확신도·판별 테스트와 함께 CEO 브리핑으로 서술할 것.`);
    }
  }
}
