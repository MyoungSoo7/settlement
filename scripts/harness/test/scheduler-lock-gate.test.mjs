// ShedLock 락 이름 유일성 게이트 — 리포 전수.
//
// 같은 이름을 두 스케줄러가 공유하면 락 보유 기간(lockAtLeastFor/lockAtMostFor) 동안 나머지가
// **조용히 스킵**된다. 예외도 로그도 없이 "그날 하나만 실행"되므로 컴파일도 CI 도 잡지 못하고,
// 운영에서 "왜 이 배치만 안 돌았지"로 뒤늦게 드러난다.
//
// 실제 사례(2026-08-06 ofDentis 레거시 분석): 한 스케줄러 클래스가 name="cancelMarketDeposit" 을
// 3개 메서드에, name="cancelEduDeposit" 을 2개 메서드에 붙이고 lockAtLeastFor 를 23~24시간으로 둬서
// 매일 그중 하나만 실행되고 있었다. 우리 트리는 현재 청정하며, 이 게이트가 그 상태를 잠근다.
//
// 같은 이름을 의도적으로 공유해 상호배제를 노리는 설계가 필요해지면 ALLOWED_SHARED 에 근거와 함께
// 등록한다 — 침묵하는 스킵이 아니라 명시적 선언이 되도록.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 의도적으로 이름을 공유하는 쌍(현재 없음) — 추가 시 반드시 근거 주석을 남긴다. */
const ALLOWED_SHARED = new Set();

const LOCK_NAME = /@SchedulerLock\s*\([^)]*?name\s*=\s*"([^"]+)"/gs;

/** 소스에서 (락 이름, 위치) 목록을 뽑는다. 주석 처리된 선언은 세지 않는다. */
export function collectLockNames(files, read) {
  const found = [];
  for (const file of files) {
    const content = read(file);
    // 라인 주석으로 죽여둔 선언 제외 — 살아있는 배치만 대상.
    const live = content.split(/\r?\n/).filter((line) => !/^\s*(\/\/|\*)/.test(line)).join('\n');
    for (const match of live.matchAll(LOCK_NAME)) {
      found.push({ name: match[1], file });
    }
  }
  return found;
}

/** 같은 이름을 2회 이상 쓰는 항목만 돌려준다. */
export function findDuplicateLockNames(entries, allowed = ALLOWED_SHARED) {
  const byName = new Map();
  for (const { name, file } of entries) {
    if (!byName.has(name)) byName.set(name, []);
    byName.get(name).push(file);
  }
  return [...byName.entries()]
    .filter(([name, files]) => files.length > 1 && !allowed.has(name))
    .map(([name, files]) => `${name} → ${files.join(', ')}`);
}

describe('ShedLock 락 이름 유일성', () => {
  // 검출기 자체가 동작하는지 먼저 못박는다 — 정규식이 아무것도 못 잡으면 게이트는 항상 통과한다.
  test('중복을 실제로 검출한다', () => {
    const entries = [
      { name: 'cancelMarketDeposit', file: 'A.java' },
      { name: 'cancelMarketDeposit', file: 'B.java' },
      { name: 'unique-one', file: 'C.java' },
    ];

    assert.deepEqual(findDuplicateLockNames(entries),
      ['cancelMarketDeposit → A.java, B.java']);
  });

  test('같은 파일 안의 중복도 검출한다(ofDentis 실제 패턴)', () => {
    const entries = [
      { name: 'cancelEduDeposit', file: 'PointScheduler.java' },
      { name: 'cancelEduDeposit', file: 'PointScheduler.java' },
    ];

    assert.equal(findDuplicateLockNames(entries).length, 1);
  });

  test('허용 목록에 등록된 이름은 통과시킨다', () => {
    const entries = [
      { name: 'shared-on-purpose', file: 'A.java' },
      { name: 'shared-on-purpose', file: 'B.java' },
    ];

    assert.deepEqual(findDuplicateLockNames(entries, new Set(['shared-on-purpose'])), []);
  });

  test('주석 처리된 선언은 세지 않는다', () => {
    const source = [
      '    // @SchedulerLock(name = "dead-one")',
      '    @SchedulerLock(name = "live-one", lockAtMostFor = "PT1H")',
    ].join('\n');

    assert.deepEqual(collectLockNames(['X.java'], () => source),
      [{ name: 'live-one', file: 'X.java' }]);
  });

  test('여러 줄에 걸친 선언도 인식한다', () => {
    const source = [
      '    @SchedulerLock(',
      '            name = "wrapped-one",',
      '            lockAtMostFor = "PT1H")',
    ].join('\n');

    assert.deepEqual(collectLockNames(['X.java'], () => source).map((e) => e.name),
      ['wrapped-one']);
  });

  // ── 리포 전수 ──
  const trackedJava = execFileSync('git', ['ls-files', '*.java'], { cwd: repoRoot })
    .toString('utf8').split('\n').filter(Boolean);
  const entries = collectLockNames(trackedJava, (f) => readFileSync(join(repoRoot, f), 'utf8'));

  test('스캔이 실제로 락 선언을 수집한다(빈 스캔으로 통과하는 것 방지)', () => {
    assert.ok(entries.length >= 10,
      `@SchedulerLock 수집 결과가 비정상적으로 적다(${entries.length}) — 정규식이 깨졌을 수 있다`);
  });

  test('리포 전체에서 락 이름이 유일하다', () => {
    assert.deepEqual(findDuplicateLockNames(entries), [],
      '락 이름이 겹치면 겹친 배치들이 서로를 굶긴다(그날 하나만 실행)');
  });
});
