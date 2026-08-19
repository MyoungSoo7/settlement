/**
 * 빌드 컨텍스트 게이트 — .dockerignore 의 "루트에만 적용되는 패턴" 함정을 막는다.
 *
 * `.dockerignore` 의 `build/` 는 **컨텍스트 루트의** build 만 뺀다. `order-service/build`,
 * `shared-common/.gradle` 같은 하위 모듈 것은 그대로 업로드된다. 이 저장소는 모듈이 20개가
 * 넘어서 그 차이가 전부다.
 *
 * 증상이 "빌드가 느리다" 였다면 이 게이트는 없었을 것이다. 실제 증상은 **빌드 실패**다.
 * Gradle 이 도는 중에(게이트 실행·병행 세션 테스트) 이미지를 빌드하면 BuildKit 이
 * `shared-common/.gradle/<버전>/executionHistory.lock` 을 읽다가 Windows 파일 잠금에 걸려
 * 컨텍스트 전송이 통째로 죽는다:
 *
 *   rpc error: read ...executionHistory.lock: The process cannot access the file
 *   because another process has locked a portion of the file
 *
 * 2026-08-19 전 서비스 재빌드가 이 이유로 반복 실패했고(settlement·operation·order),
 * glob 패턴(`**` 접두) 추가 후 컨텍스트가 162.68MB → 878kB 로 줄며 모두 통과했다. 고약한 점은
 * 산발적이라는 것이다 — Gradle 이 안 도는 순간에 빌드하면 멀쩡히 성공해서, 원인을 컨텍스트가
 * 아니라 그때그때의 모듈 코드에서 찾게 된다.
 *
 * 검사 대상은 **실제로 저장소에 존재하는 중첩 디렉터리**로 한정한다. 없는 것을 요구하면
 * 게이트가 상상 속 규칙을 강제하게 된다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * 하위 경로에 쌓이는, 이미지가 절대 쓰지 않는 디렉터리들.
 * - build/target: Gradle 산출물. 컨테이너 안에서 다시 빌드하므로 호스트 것은 무의미하다.
 * - .gradle: 위 락 파일이 사는 곳 — 이 게이트의 존재 이유.
 * - node_modules: Dockerfile 은 JVM 모듈만 굽는다(프론트는 nginx + dist 바인드 마운트).
 */
const MUST_EXCLUDE_NESTED = ['build', '.gradle', 'node_modules', 'target'];

/** 주석·공백을 걷어낸 패턴 목록. */
function dockerignorePatterns() {
  return readFileSync(join(REPO_ROOT, '.dockerignore'), 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'));
}

/** 저장소 1단계 하위(모듈 디렉터리)에서 해당 이름의 디렉터리를 실제로 갖고 있는 곳들. */
function modulesContaining(dirName) {
  const found = [];
  for (const entry of readdirSync(REPO_ROOT, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    let children;
    try {
      children = readdirSync(join(REPO_ROOT, entry.name), { withFileTypes: true });
    } catch {
      continue; // 권한·경합으로 못 읽는 디렉터리는 이 게이트의 관심사가 아니다
    }
    if (children.some((c) => c.isDirectory() && c.name === dirName)) found.push(entry.name);
  }
  return found;
}

test('.dockerignore 는 하위 모듈의 산출물·캐시 디렉터리를 ** 패턴으로 제외한다', () => {
  const patterns = dockerignorePatterns();
  assert.ok(patterns.length > 0, '.dockerignore 에서 패턴을 하나도 읽지 못했다 — 파싱 점검 필요');

  const checked = [];
  const missing = [];

  for (const name of MUST_EXCLUDE_NESTED) {
    const owners = modulesContaining(name);
    if (owners.length === 0) continue; // 저장소에 없으면 강제하지 않는다

    checked.push(`${name}(${owners.length}곳)`);
    const covered = patterns.some((p) => p === `**/${name}/` || p === `**/${name}`);
    if (!covered) {
      missing.push(`${name} — 예: ${owners[0]}/${name} (루트 전용 '${name}/' 만으로는 안 빠진다)`);
    }
  }

  // 검사가 실제 대상에 닿았음을 먼저 증명한다. 0곳이면 통과가 아니라 게이트 고장이다.
  assert.ok(
    checked.length > 0,
    `중첩 대상 디렉터리를 하나도 찾지 못했다(${MUST_EXCLUDE_NESTED.join(', ')}) — 탐색 로직 점검 필요`,
  );

  assert.deepEqual(missing, [], `\n하위 모듈이 빌드 컨텍스트로 올라간다:\n  ${missing.join('\n  ')}\n`);
});
