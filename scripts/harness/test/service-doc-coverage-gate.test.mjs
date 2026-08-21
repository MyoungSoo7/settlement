// 서비스↔문서 커버리지 게이트 — 리포 전수.
//
// 막는 것: "서비스는 있는데 그 서비스를 설명하는 문서가 없는 상태".
//
// 2026-08-22 감사에서 `docs/plan/prd/` 는 25종이었고 서비스 디렉토리는 27개였다. 빠진 둘
// (education·receipt-ocr)은 최근 것이라 빠진 게 아니라 **커버리지를 대조하는 주체가 없어서** 빠졌다.
// 그리고 문서가 없는 동안 두 서비스 모두 배선 결함을 하나씩 품고 있었다 — education 은 공개
// 이벤트가 Kafka 로 나가지 않았고, receipt-ocr 은 CI 가 한 번도 코드를 돌리지 않았다. 역산 PRD 를
// 쓰는 행위 자체가 그 결함을 드러냈으므로, "문서가 없다"는 문서의 문제로 끝나지 않는다.
//
// 어떤 코드도 "틀리지" 않기 때문에 컴파일도 테스트도 잡지 못한다 — harness-audit 이
// settings.gradle.kts 로 모듈 로스터 드리프트를 잡는 것과 같은 축이다.
//
// 정본: docs/plan/prd-seed-drift-audit.md §6.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const PRD_DIR = join(REPO_ROOT, 'docs', 'plan', 'prd');
const SEED_DIR = join(REPO_ROOT, 'docs', 'plan', 'seeds');

/**
 * PRD 파일명이 디렉토리명과 다른 서비스와 그 사유.
 * 여기 등록하는 것은 "이 서비스의 PRD 는 저 이름으로 있다"는 명시적 선언이다.
 */
const PRD_ALIAS = new Map([
  ['settlement-service', 'settlement-core'], // 정산 코어는 도메인명으로 부른다(자매 문서 규약)
]);

/**
 * Seed 가 없어도 되는 서비스와 그 사유.
 * **비어 있는 것이 정상이다** — 등록은 "이 서비스는 as-is 사양을 결정화하지 않는다"는 선언이며,
 * 그 선언에는 근거가 있어야 한다. 근거 없이 등록하는 것은 게이트를 끄는 것과 같다.
 */
const SERVICES_WITHOUT_SEED = new Map([]);

/** 작업트리 기준 서비스 디렉토리 — git ls-files 는 병행 세션의 미추적 파일을 못 본다. */
export function serviceDirs(root = REPO_ROOT) {
  return readdirSync(root, { withFileTypes: true })
    .filter((e) => e.isDirectory() && e.name.endsWith('-service'))
    .map((e) => e.name)
    .sort();
}

/**
 * 빌드 매니페스트로 서비스를 식별한다 — 빈 껍데기 디렉토리를 문서 대상으로 세지 않으면서
 * **폴리글랏을 빼먹지 않기 위해서**다.
 *
 * 처음에는 `src/` 존재로 판정했는데, Go 서비스(market-stream·payment-webhook)는 `cmd/`+`internal/`
 * 레이아웃이라 통째로 스캔에서 빠졌다. 그 상태에서도 커버리지 검사는 "통과"했다 — 대상이 줄면
 * 게이트는 조용히 헐거워진다. 그래서 언어별 매니페스트를 전부 인정한다.
 */
export const BUILD_MANIFESTS = ['build.gradle.kts', 'go.mod', 'pyproject.toml'];

function hasSources(name, root = REPO_ROOT) {
  return BUILD_MANIFESTS.some((m) => existsSync(join(root, name, m)));
}

export function prdPathFor(name) {
  return join(PRD_DIR, `${PRD_ALIAS.get(name) ?? name}.md`);
}

/** Seed 디렉토리의 yaml 파일명들. */
function seedFileNames() {
  return readdirSync(SEED_DIR).filter((f) => f.endsWith('.seed.yaml')).sort();
}

/**
 * 서비스 하나에 딸린 Seed yaml 들 — 한 서비스가 여러 Seed 를 가질 수 있다(settlement 4종).
 *
 * 파일명 목록을 인자로 받는다(디렉토리를 직접 읽지 않는다) — 접두사가 겹치는 서비스
 * (`settlement-service` vs `settlement-anomaly-service`)를 섞지 않는지 테스트로 못박기 위해서다.
 */
export function seedsFor(name, fileNames) {
  return fileNames.filter((f) => f.startsWith(`${name}-`)).sort();
}

describe('서비스↔문서 커버리지', () => {
  // 화살표로 감싼다 — filter 는 (값, 인덱스, 배열)을 넘기므로 hasSources 를 그대로 주면
  // 인덱스가 두 번째 인자(root)를 덮어쓴다.
  const services = serviceDirs().filter((name) => hasSources(name));

  // ── 스캔 자체 검증 — 대상에 도달하지 못한 게이트는 영원히 통과한다 ──
  test('스캔이 실제로 서비스 디렉토리에 도달했다', () => {
    assert.ok(services.length >= 25,
      `서비스 디렉토리 수집이 적다(${services.length}) — 스캔이 깨졌을 수 있다`);
  });

  test('스캔이 폴리글랏(Go·Python)까지 도달했다', () => {
    // Java 만 잡히면 커버리지 검사가 조용히 절반만 돈다 — 실제로 그 상태였다.
    for (const svc of ['market-stream-service', 'payment-webhook-service', 'forecast-service']) {
      assert.ok(services.includes(svc), `${svc} 가 스캔에서 빠졌다 — BUILD_MANIFESTS 를 확인하세요`);
    }
  });

  test('스캔이 실제로 문서 디렉토리에 도달했다', () => {
    assert.ok(readdirSync(PRD_DIR).filter((f) => f.endsWith('.md')).length >= 25, 'PRD 수집이 적다');
    assert.ok(readdirSync(SEED_DIR).filter((f) => f.endsWith('.seed.yaml')).length >= 25, 'Seed 수집이 적다');
  });

  test('[자기검증] 별칭 없는 이름은 같은 이름의 PRD 를 찾는다', () => {
    assert.equal(prdPathFor('card-service'), join(PRD_DIR, 'card-service.md'));
    assert.equal(prdPathFor('settlement-service'), join(PRD_DIR, 'settlement-core.md'));
  });

  test('[자기검증] Seed 매칭이 접두사가 겹치는 서비스를 섞지 않는다', () => {
    // 'settlement-service-' 와 'settlement-anomaly-service-' 는 앞부분이 겹친다 —
    // startsWith 를 서비스명이 아니라 '서비스명-' 으로 걸어야 갈린다.
    const names = [
      'settlement-service-recon.seed.yaml',
      'settlement-anomaly-service-scoring.seed.yaml',
    ];
    assert.deepEqual(seedsFor('settlement-service', names), ['settlement-service-recon.seed.yaml']);
    assert.deepEqual(seedsFor('settlement-anomaly-service', names),
      ['settlement-anomaly-service-scoring.seed.yaml']);
  });

  // ── 커버리지 ──
  test('모든 서비스에 역산 PRD 가 있다', () => {
    const missing = services.filter((s) => !existsSync(prdPathFor(s)));

    assert.deepEqual(missing, [],
      '서비스가 있는데 PRD 가 없습니다. docs/plan/prd/<서비스>.md 를 역산해 추가하거나, '
      + '파일명이 다르다면 이 게이트의 PRD_ALIAS 에 사유와 함께 등록하세요.');
  });

  test('모든 서비스에 Seed 가 있거나 사유와 함께 등록돼 있다', () => {
    const names = seedFileNames();
    const missing = services.filter((s) => seedsFor(s, names).length === 0 && !SERVICES_WITHOUT_SEED.has(s));

    assert.deepEqual(missing, [],
      'Seed 가 없는 서비스입니다. docs/plan/seeds/<서비스>-<주제>.seed.{md,yaml} 를 추가하거나, '
      + '이 게이트의 SERVICES_WITHOUT_SEED 에 사유와 함께 등록하세요.');
  });

  // ── allowlist 위생 — 죽은 항목은 게이트를 조용히 헐겁게 만든다 ──
  test('PRD_ALIAS 에 죽은 항목이 없다', () => {
    const stale = [...PRD_ALIAS.keys()].filter((s) => !services.includes(s));
    assert.deepEqual(stale, [], '이미 사라진 서비스가 별칭에 남아 있습니다');
  });

  test('SERVICES_WITHOUT_SEED 에 죽은 항목이 없다', () => {
    const names = seedFileNames();
    const stale = [...SERVICES_WITHOUT_SEED.keys()]
      .filter((s) => !services.includes(s) || seedsFor(s, names).length > 0);
    assert.deepEqual(stale, [],
      'Seed 가 생겼거나 서비스가 사라졌는데 면제 목록에 남아 있습니다 — 면제를 지우세요');
  });

  test('모든 Seed yaml 에 짝이 되는 md 가 있다', () => {
    const orphan = seedFileNames()
      .filter((f) => !existsSync(join(SEED_DIR, f.replace(/\.yaml$/, '.md'))));

    assert.deepEqual(orphan, [], 'Seed yaml 은 있는데 사람이 읽을 md 가 없습니다');
  });

  test('모든 Seed 가 실재하는 서비스에 속한다', () => {
    const orphan = seedFileNames()
      .filter((f) => !services.some((s) => f.startsWith(`${s}-`)));

    assert.deepEqual(orphan, [], '어느 서비스에도 속하지 않는 Seed 입니다');
  });
});

