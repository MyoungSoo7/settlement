// Outbox 폴러 배선 게이트 — 리포 전수.
//
// 막는 것: "Outbox 행은 쌓이는데 아무도 Kafka 로 보내지 않는 상태".
//
// Outbox 패턴은 두 조각이다 — ① DB 트랜잭션 안에서 `outbox_events` INSERT, ② 폴러가 PENDING 행을
// 집어 Kafka 발행. ①만 있으면 **컴파일도 되고 테스트도 통과하고 API 도 200 을 준다.** 이벤트만
// 영원히 나가지 않는다. 하류가 조용히 굶는데 상류에는 아무 증상이 없다.
//
// 실측 사례(2026-08-22): education-service 가 정확히 이 상태였다. `OutboxBackedEducationEventPublisher`
// 는 정상 동작해 PENDING 행을 넣지만 spring-kafka 의존·bootstrap 설정·폴러 빈이 전부 없다. 스캔이
// `github.lms.lemuel.education` 으로 한정돼 shared-common 의 `OutboxPublisherScheduler`(@Component)가
// 붙지 않았고, `PersistenceConfig` 도 이를 들이지 않았다. 전체 스캔인 서비스들은 우연히 무사했다.
// 결과적으로 `lemuel.education.course_published` 는 카탈로그에 소유 토픽으로 등재돼 있으면서 한 번도
// 생산된 적이 없다.
//
// 왜 "주석이 아니라 애노테이션"인가: 첫 검출기는 소스 전문을 정규식으로 훑어 company-service 를
// 통과시켰는데, 근거가 **자바독 주석의 'OutboxPublisherScheduler 가 발행한다'** 라는 문장이었다.
// 실제 배선은 `PersistenceConfig` 의 `@ComponentScan("github.lms.lemuel.common.outbox")` 였다 —
// 답은 맞고 이유는 틀렸다. 이유가 틀린 검출기는 다음에 틀린 답을 낸다. 그래서 주석을 걷어내고
// 빈 도달 경로(전체 스캔 · scanBasePackages · @ComponentScan · @Import)만 인정한다.
//
// 정본: docs/plan/prd-seed-drift-audit.md §6 · docs/plan/prd/education-service.md G-1.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** 폴러 빈이 사는 패키지·클래스 — shared-common 의 @Component 라 스캔에 잡혀야 한다. */
const POLLER_PKG = 'github.lms.lemuel.common.outbox.application.service';
const POLLER_CLASS = 'OutboxPublisherScheduler';

/**
 * 폴러가 배선되지 않았음이 **확인된** 서비스와 그 사유.
 *
 * 이것은 설계 결정이 아니라 **기록된 결함**이다 — 여기 등록하는 것은 "괜찮다"가 아니라 "알고
 * 있고 아직 못 고쳤다"는 뜻이다. 고치면 이 항목을 반드시 지워야 한다(아래 죽은 항목 검사가 강제).
 * 새 서비스를 여기 넣어 게이트를 통과시키는 것은 우회다.
 */
const KNOWN_UNWIRED = new Map([
  ['education-service',
    '2026-08-22 확인 — spring-kafka 의존·bootstrap·폴러 빈 3축 모두 없음. '
    + '배선하면 lemuel.education.course_published 가 브로커에 생성되며 파티션 수가 소급 불가로 '
    + '고정된다(ADR 0035) — 별도 결정 필요. docs/plan/prd/education-service.md G-1 / T-1'],
]);

/** 블록·라인 주석 제거 — "주석이 배선을 대신하지 못한다". */
export function stripComments(src) {
  return String(src).replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

const covers = (base, pkg) => pkg === base || pkg.startsWith(`${base}.`);

/**
 * 이 소스가 폴러 빈을 컨텍스트에 들이는 경로를 반환한다(없으면 빈 배열).
 * 근거를 문자열로 남기는 이유 — 통과했을 때 **무엇 덕분에 통과했는지**가 보여야 한다.
 */
export function pollerGrants(rawSrc) {
  const src = stripComments(rawSrc);
  const grants = [];

  const boot = src.match(/@SpringBootApplication(\s*\(([\s\S]*?)\))?/);
  if (boot) {
    const args = boot[2] || '';
    if (!/scanBasePackages/.test(args)) grants.push('@SpringBootApplication 전체 스캔');
    else {
      for (const m of args.matchAll(/"([^"]+)"/g)) {
        if (covers(m[1], POLLER_PKG)) grants.push(`scanBasePackages="${m[1]}"`);
      }
    }
  }
  for (const m of src.matchAll(/@ComponentScan\s*\(([\s\S]*?)\)/g)) {
    for (const s of m[1].matchAll(/"([^"]+)"/g)) {
      if (covers(s[1], POLLER_PKG)) grants.push(`@ComponentScan("${s[1]}")`);
    }
  }
  for (const m of src.matchAll(/@Import\s*\(([\s\S]*?)\)/g)) {
    if (m[1].includes(POLLER_CLASS)) grants.push(`@Import(${POLLER_CLASS})`);
  }
  return grants;
}

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walkJava(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** Outbox 에 쓰는 서비스만 대상 — 발행하지 않는 서비스에 폴러를 요구할 이유가 없다. */
function outboxProducers() {
  const result = [];
  for (const entry of readdirSync(REPO_ROOT, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.endsWith('-service')) continue;
    const files = walkJava(join(REPO_ROOT, entry.name, 'src', 'main'));
    if (!files.length) continue;

    let writesOutbox = false;
    const grants = [];
    for (const f of files) {
      const raw = readFileSync(f, 'utf8');
      if (stripComments(raw).includes('SaveOutboxEventPort')) writesOutbox = true;
      grants.push(...pollerGrants(raw));
    }
    if (!writesOutbox) continue;

    const gradlePath = join(REPO_ROOT, entry.name, 'build.gradle.kts');
    const gradle = existsSync(gradlePath) ? readFileSync(gradlePath, 'utf8') : '';
    const ymlPath = join(REPO_ROOT, entry.name, 'src', 'main', 'resources', 'application.yml');
    const yml = existsSync(ymlPath) ? readFileSync(ymlPath, 'utf8') : '';

    result.push({
      name: entry.name,
      grants,
      hasKafkaDependency: /spring-kafka|spring-boot-starter-kafka/.test(gradle),
      hasBootstrapConfig: /bootstrap-servers/.test(yml),
    });
  }
  return result.sort((a, b) => a.name.localeCompare(b.name));
}

/** 세 축이 모두 서야 이벤트가 실제로 나간다. */
export function unwiredAxes(svc) {
  const axes = [];
  if (!svc.grants.length) axes.push('폴러 빈 도달 경로 없음');
  if (!svc.hasKafkaDependency) axes.push('spring-kafka 의존 없음');
  if (!svc.hasBootstrapConfig) axes.push('bootstrap-servers 설정 없음');
  return axes;
}

describe('Outbox 폴러 배선', () => {
  const producers = outboxProducers();

  // ── 검출기 자체 검증 — 주석을 배선으로 읽으면 게이트가 영원히 통과한다 ──
  test('[자기검증] 자바독에 적힌 폴러 이름을 배선으로 읽지 않는다', () => {
    const src = [
      '/**',
      ` * shared-common 의 ${POLLER_CLASS} 가 이 행을 집어 발행한다.`,
      ' */',
      '@Configuration',
      'public class Foo { }',
    ].join('\n');

    assert.deepEqual(pollerGrants(src), []);
  });

  test('[자기검증] 라인 주석의 @Import 도 배선이 아니다', () => {
    assert.deepEqual(pollerGrants(`// @Import(${POLLER_CLASS}.class)\n@Configuration class A {}`), []);
  });

  test('[자기검증] 실제 배선 4경로를 모두 인정한다', () => {
    assert.deepEqual(pollerGrants('@SpringBootApplication\nclass A {}'),
      ['@SpringBootApplication 전체 스캔']);
    assert.deepEqual(pollerGrants('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.common")\nclass A {}'),
      ['scanBasePackages="github.lms.lemuel.common"']);
    assert.deepEqual(pollerGrants('@ComponentScan(basePackages = "github.lms.lemuel.common.outbox")\nclass A {}'),
      ['@ComponentScan("github.lms.lemuel.common.outbox")']);
    assert.deepEqual(pollerGrants(`@Import({${POLLER_CLASS}.class, Other.class})\nclass A {}`),
      [`@Import(${POLLER_CLASS})`]);
  });

  test('[자기검증] 폴러 패키지를 덮지 않는 스캔은 인정하지 않는다', () => {
    assert.deepEqual(pollerGrants('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.education")\nclass A {}'), []);
    // 접두사가 우연히 겹치는 패키지를 덮는 것으로 오인하지 않는다.
    assert.deepEqual(pollerGrants('@ComponentScan(basePackages = "github.lms.lemuel.commonx")\nclass A {}'), []);
  });

  // ── 스캔 자체 검증 ──
  test('스캔이 실제로 Outbox 발행 서비스에 도달했다', () => {
    assert.ok(producers.length >= 8,
      `Outbox 발행 서비스 수집이 적다(${producers.length}) — 검출기가 깨졌을 수 있다`);
    assert.ok(producers.some((p) => p.name === 'settlement-service'),
      'settlement-service 가 발행 서비스로 잡히지 않았다');
  });

  // ── 본 검사 ──
  test('Outbox 에 쓰는 서비스는 폴러·Kafka 의존·bootstrap 설정을 갖춘다', () => {
    const unwired = producers
      .filter((p) => unwiredAxes(p).length > 0 && !KNOWN_UNWIRED.has(p.name))
      .map((p) => `${p.name}: ${unwiredAxes(p).join(' / ')}`);

    assert.deepEqual(unwired, [],
      'Outbox 행은 쌓이는데 발행되지 않는 상태입니다. 폴러를 배선하세요 — '
      + `전체 스캔이 아니면 @Import(${POLLER_CLASS}.class) 또는 `
      + `@ComponentScan("${POLLER_PKG.replace('.application.service', '')}") 가 필요합니다.`);
  });

  // ── allowlist 위생 ──
  test('KNOWN_UNWIRED 에 죽은 항목이 없다 — 고쳤으면 지워야 한다', () => {
    const fixed = [...KNOWN_UNWIRED.keys()].filter((name) => {
      const svc = producers.find((p) => p.name === name);
      return !svc || unwiredAxes(svc).length === 0;
    });

    assert.deepEqual(fixed, [],
      '배선이 끝났거나 서비스가 사라졌는데 KNOWN_UNWIRED 에 남아 있습니다 — 항목을 지우세요');
  });

  test('KNOWN_UNWIRED 의 모든 항목에 사유가 있다', () => {
    const bare = [...KNOWN_UNWIRED.entries()].filter(([, why]) => !why || why.trim().length < 20);
    assert.deepEqual(bare.map(([n]) => n), [],
      '사유 없는 면제는 게이트를 끄는 것과 같습니다');
  });
});
