#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const REQUIRED_CASES = [
  'seed-gate-create',
  'seed-gate-reuse',
  'user-adoption',
  'first-cycle-skip',
  'threshold-boundary',
  'safety-cycle-5',
];
const CANONICAL_CONTRACT_CASES = {
  'seed-gate-create': 'incomplete-seed->socrates',
  'seed-gate-reuse': 'complete-seed->evolve-step',
  'user-adoption': 'candidate-requires-explicit-user-approval',
  'first-cycle-skip': 'cycle-1->skip-comparison',
  'threshold-boundary': 'similarity>=0.85->convergence',
  'safety-cycle-5': 'cycle-5-not-converged->safety_valve',
};

function assertPath(value, label) {
  if (typeof value !== 'string' || value.length === 0 || isAbsolute(value) || value.includes('\\') || value.split('/').includes('..')) {
    throw new Error(`manifest ${label} must be a non-empty repository-relative POSIX path`);
  }
}

export function validateManifest(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || value.schemaVersion !== 1) {
    throw new Error('manifest schemaVersion must be 1');
  }
  if (!Array.isArray(value.requiredTrackedFiles) || !Array.isArray(value.criticalContractPairs)) {
    throw new Error('manifest arrays are required');
  }
  const seen = new Set();
  for (const path of value.requiredTrackedFiles) {
    assertPath(path, 'requiredTrackedFiles entry');
    if (seen.has(path)) throw new Error(`manifest duplicate path: ${path}`);
    seen.add(path);
  }
  for (const pair of value.criticalContractPairs) {
    if (!pair || typeof pair !== 'object' || typeof pair.contract !== 'string' || !pair.contract) {
      throw new Error('manifest contract pair is invalid');
    }
    assertPath(pair.claude, `${pair.contract}.claude`);
    assertPath(pair.codex, `${pair.contract}.codex`);
    if (!seen.has(pair.claude) || !seen.has(pair.codex)) {
      throw new Error(`manifest contract files must be required tracked files: ${pair.contract}`);
    }
    if (!Object.hasOwn(pair, 'facts') && !deepEqual(pair.contractCases, CANONICAL_CONTRACT_CASES)) {
      throw new Error(`manifest ${pair.contract} must contain the exact canonical contract cases`);
    }
  }
  return value;
}

export function extractHarnessContract(markdown) {
  const blocks = [...String(markdown).matchAll(/```harness-contract\s*\r?\n([\s\S]*?)\r?\n```/g)];
  if (blocks.length !== 1) throw new Error(`harness-contract block count must be 1, got ${blocks.length}`);
  try {
    return JSON.parse(blocks[0][1]);
  } catch (error) {
    throw new Error(`harness-contract JSON is invalid: ${error.message}`);
  }
}

export function readContractCases(json) {
  let value;
  try {
    value = JSON.parse(json);
  } catch (error) {
    throw new Error(`contract cases JSON is invalid: ${error.message}`);
  }
  const cases = Array.isArray(value) ? value : value?.cases;
  if (!Array.isArray(cases)) throw new Error('contract cases must be an array or a cases array');
  const result = {};
  for (const item of cases) {
    if (!item || typeof item.contractCase !== 'string' || typeof item.expectedTransition !== 'string') {
      throw new Error('contract case requires contractCase and expectedTransition strings');
    }
    if (Object.hasOwn(result, item.contractCase)) throw new Error(`duplicate contract case: ${item.contractCase}`);
    result[item.contractCase] = item.expectedTransition;
  }
  for (const id of REQUIRED_CASES) if (!Object.hasOwn(result, id)) throw new Error(`missing contract case: ${id}`);
  for (const id of Object.keys(result)) if (!REQUIRED_CASES.includes(id)) throw new Error(`unexpected contract case: ${id}`);
  return result;
}

function deepEqual(a, b) {
  if (Object.is(a, b)) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
  const ak = Object.keys(a);
  const bk = Object.keys(b);
  return ak.length === bk.length && ak.every((key) => Object.hasOwn(b, key) && deepEqual(a[key], b[key]));
}

function trackedFiles(repoRoot) {
  const raw = execFileSync('git', ['-C', repoRoot, 'ls-files', '-z'], { encoding: 'utf8' });
  return raw.split('\0').filter(Boolean).sort();
}

function claimNumber(status, patterns) {
  for (const pattern of patterns) {
    const match = status.match(pattern);
    if (match) return Number(match[1].replaceAll(',', ''));
  }
  return null;
}

/**
 * YAML 주석(`#` 이후)을 제거한다 — 따옴표 안의 `#` 은 값이므로 남긴다.
 *
 * 배선 판정에 원문을 쓰면 "왜 구독하지 않는지" 적어 둔 주석이 구독 근거로 읽힌다. 설명이 자세한
 * 설정일수록 오탐이 늘어나는 구조였다(deposit application.yml, 2026-08-22).
 */
export function stripYamlComments(yaml) {
  return String(yaml).split('\n').map((line) => {
    let quote = null;
    for (let i = 0; i < line.length; i += 1) {
      const ch = line[i];
      if (quote) {
        if (ch === '\\') i += 1;
        else if (ch === quote) quote = null;
      } else if (ch === '"' || ch === "'") quote = ch;
      else if (ch === '#') return line.slice(0, i);
    }
    return line;
  }).join('\n');
}

export function parseGradleModules(settings) {
  // "includeBuild(" 은 "include(" 부분문자열을 포함하지 않으므로 오매치 없음
  const block = String(settings).match(/include\(([\s\S]*?)\)/);
  if (!block) return [];
  return [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

// settings.gradle.kts 모듈 로스터 ↔ 문서 트리(CLAUDE.md·STRUCTURE.md) 대조 —
// 서비스 모듈을 추가/삭제하고 문서 트리를 안 고치면 audit 이 실패한다.
function validateModuleRoster(read, trackedSet, errors) {
  if (!trackedSet.has('settings.gradle.kts')) return;
  const modules = parseGradleModules(read('settings.gradle.kts'));
  // 경로 주의: 구조 정본은 저장소 루트의 STRUCTURE.md 다(2026-08-13 docs/ 에서 승격).
  // 경로가 어긋나면 trackedSet 조회가 빗나가 이 문서는 검사된 적 없이 조용히 skip 된다.
  for (const doc of ['CLAUDE.md', 'STRUCTURE.md']) {
    if (!trackedSet.has(doc)) continue;
    const content = read(doc);
    for (const module of modules) {
      if (!content.includes(module)) {
        errors.push(`${doc} module roster missing: ${module} (declared in settings.gradle.kts)`);
      }
    }
  }
}

// settlement-service 서브도메인 로스터 ↔ 문서 트리 대조 —
// 모듈은 settings.gradle.kts 가 정본이라 위 검사가 잡지만, 한 모듈 '안'의 서브도메인은 어디에도
// 선언이 없어 코드에만 존재한다. 실제로 tax(부가세·원천징수 — 실지급액을 바꾼다)·closing·recovery 가
// 문서에 없는 채로 오래 방치됐다(2026-08-12 역산에서 발견: 문서 7개 vs 코드 12개).
// 낡은 요약은 "정산은 그 7개가 전부"라는 잘못된 범위 판단을 유도하므로 기계로 막는다.
export function parseSettlementDomains(tracked) {
  // DOMAIN_BASE 는 이 파일 아래쪽에서 선언되므로 톱레벨 상수로 빼지 않는다(TDZ) — 호출 시점엔 이미 초기화됨.
  const prefix = `settlement-service/${DOMAIN_BASE}`;
  const domains = new Set();
  for (const file of tracked) {
    if (!file.startsWith(prefix)) continue;
    const rest = file.slice(prefix.length);
    if (!rest.includes('/')) continue;                  // 루트 직속 파일(Application) 제외
    const segment = rest.slice(0, rest.indexOf('/'));
    if (/^[a-z][a-z0-9]*$/.test(segment)) domains.add(segment);  // 점으로 시작하는 도구 디렉터리 제외
  }
  return [...domains].sort();
}

function validateSettlementDomainRoster(read, tracked, trackedSet, errors) {
  const domains = parseSettlementDomains(tracked);
  if (!domains.length) return;
  // 문서마다 표기가 다르다(CLAUDE.md 는 한 줄 `·` 나열, STRUCTURE.md 는 다음 줄 중괄호 목록) —
  // settlement-service 트리 줄부터 2줄을 창으로 잡아 그 안에서만 찾는다(다른 문단 오탐 방지).
  for (const doc of ['CLAUDE.md', 'STRUCTURE.md']) {
    if (!trackedSet.has(doc)) continue;
    const lines = read(doc).split(/\r?\n/);
    // 앵커는 '모듈 트리 줄'이어야 한다 — 'settlement-service/' 만으로 찾으면 가드레일 문단의
    // build.gradle.kts 언급에 먼저 걸린다. 포트(8082)를 함께 요구해 트리 줄로 고정한다.
    const anchor = lines.findIndex((line) => line.includes('settlement-service/') && line.includes('8082'));
    if (anchor < 0) continue;
    const window = lines.slice(anchor, anchor + 3).join('\n');
    for (const domain of domains) {
      if (!window.includes(domain)) {
        errors.push(`${doc} settlement 서브도메인 누락: ${domain} (코드에 존재하나 문서 트리에 없음)`);
      }
    }
  }
}

// HARNESS.md 라우팅 맵(🤖📘⌘ 아이콘 줄)의 backtick 진입점 토큰을 실존 검증 —
// 에이전트·스킬·커맨드를 삭제/개명하고 라우팅 맵을 안 고치면 audit 이 실패한다.
// 토큰 규칙: `name`(agents/skills/commands 중 하나) · `/name`(커맨드 전용).
// 점(.)·슬래시(/)·중괄호가 섞인 토큰(파일명·플레이스홀더)은 대상 밖 — 수동 스니펫과 동일 스코프.
export function parseRoutingEntrypoints(markdown) {
  const tokens = new Set();
  for (const line of String(markdown).split(/\r?\n/)) {
    if (!/[🤖📘⌘]/u.test(line)) continue;
    for (const match of line.matchAll(/`(\/?[a-z][a-z0-9-]+)`/g)) tokens.add(match[1]);
  }
  return [...tokens];
}

function validateRoutingMap(read, trackedSet, errors) {
  if (!trackedSet.has('HARNESS.md')) return;
  for (const token of parseRoutingEntrypoints(read('HARNESS.md'))) {
    const name = token.startsWith('/') ? token.slice(1) : token;
    const command = trackedSet.has(`.claude/commands/${name}.md`);
    const resolved = token.startsWith('/')
      ? command
      : command || trackedSet.has(`.claude/agents/${name}.md`) || trackedSet.has(`.claude/skills/${name}/SKILL.md`);
    if (!resolved) errors.push(`HARNESS.md routing map dangling: ${token} (진입점이 agents/skills/commands 에 없음)`);
  }
}

// pre-commit Layer 2(copilot 플러그인 가드) 경로 ↔ 실제 추적 위치 대조 —
// 훅은 미존재 경로를 조용히 skip 하므로(`[ -f ]`), 플러그인 트리를 옮기고 훅 목록을 안 고치면
// 가드가 사라진 것도 모른 채 커밋이 통과한다. 플러그인이 추적되지 않는 저장소(플러그인 독립
// fresh clone)에서는 대조 대상이 없으므로 건너뛴다.
export function parseHookPluginGuards(hook) {
  return [...String(hook).matchAll(/"([\w./-]*pre-commit\.mjs)"/g)].map((m) => m[1]);
}

function validatePluginGuardPaths(read, tracked, trackedSet, errors) {
  if (!trackedSet.has('scripts/harness/hooks/pre-commit')) return;
  const guards = tracked.filter((p) => /(?:^|\/)(?:settlement|invest)-copilot\/hooks\/guards\/pre-commit\.mjs$/.test(p));
  if (guards.length === 0) return;
  const referenced = new Set(parseHookPluginGuards(read('scripts/harness/hooks/pre-commit')));
  for (const guard of guards) {
    if (!referenced.has(guard)) {
      errors.push(`pre-commit Layer 2 경로 드리프트: ${guard} (훅 목록에 없어 조용히 skip 됨)`);
    }
  }
}

// 문서 → 저장소 노드 간선. 마크다운 링크 `[텍스트](경로)` 의 대상이 추적 그래프 안에 있는지 본다.
// 링크 대상만 보고 backtick 경로는 보지 않는다 — 산문 속 경로는 예시·플레이스홀더가 섞여 소음이 크다.
// 제목(`[x](a.md "제목")`)은 잘라내고, 외부 URL·앵커·플레이스홀더(<>{}*$)는 대상이 아니다.
export function parseDocLinks(markdown) {
  const targets = [];
  for (const match of String(markdown).matchAll(/\]\(([^)\n]+)\)/g)) {
    const target = match[1].trim().split(/\s+/)[0];
    if (!target || target.startsWith('#')) continue;
    if (target.includes('://') || target.startsWith('mailto:')) continue;
    if (/[<>{}*$]/.test(target)) continue;
    targets.push(target);
  }
  return targets;
}

// 문서 기준 상대경로를 저장소 루트 기준으로 접는다. OS 경로 API 를 쓰지 않는다 —
// 저장소 경로는 항상 POSIX 이고, Windows 에서 path.join 을 태우면 구분자가 섞인다.
function resolveDocTarget(doc, target) {
  const out = [];
  for (const segment of [...doc.split('/').slice(0, -1), ...target.split('/')]) {
    if (!segment || segment === '.') continue;
    if (segment === '..') {
      if (out.length === 0) return null; // 저장소 밖 — 판정 대상 아님
      out.pop();
      continue;
    }
    out.push(segment);
  }
  return out.join('/');
}

/**
 * 링크가 추적 그래프를 벗어나면 보고한다. 두 유형을 구분하는 것이 이 검사의 핵심이다.
 *
 * <p>`dangling` 은 대상이 아예 없는 경우고, `untracked` 는 디스크에는 있지만 추적되지 않는 경우다.
 * 후자가 더 위험하다 — 작성자 화면에서는 링크가 열리므로 육안 리뷰로 절대 잡히지 않고,
 * clone 한 사람에게만 깨진다. 실제로 gitignore 된 경로를 "정본"으로 가리키는 문서가 나왔다.
 */
function validateDocLinks(root, read, tracked, trackedSet, manifest, errors) {
  const ignorePrefixes = manifest.docLinkIgnorePrefixes ?? [];
  const dirs = new Set();
  for (const path of tracked) {
    const parts = path.split('/');
    for (let i = 1; i < parts.length; i += 1) dirs.add(parts.slice(0, i).join('/'));
  }
  // 에이전트 지시서(.claude/·.codex/)는 스캔하지 않는다 — 그 링크는 저장소 참조가 아니라
  // "이런 파일을 만들어라"는 산출물 명세인 경우가 많아, 검사하면 템플릿마다 예외를 등록하게 된다.
  // 그 트리의 참조 무결성은 위 referenceSources 검사(scripts/harness 경로)가 담당한다.
  for (const doc of tracked.filter((p) => p.endsWith('.md') && !/^\.(claude|codex)\//.test(p))) {
    let text;
    try {
      text = read(doc);
    } catch {
      continue;
    }
    for (const raw of parseDocLinks(text)) {
      const target = raw.split('#')[0].split('?')[0];
      if (!target) continue;
      const resolved = resolveDocTarget(doc, target);
      if (resolved === null || resolved === '') continue;
      if (ignorePrefixes.some((prefix) => resolved.startsWith(prefix))) continue;
      if (trackedSet.has(resolved) || dirs.has(resolved)) continue;
      errors.push(existsSync(resolve(root, ...resolved.split('/')))
        ? `doc link untracked: ${doc} → ${target} (디스크에만 존재 — 로컬에서만 열리고 clone 하면 깨진다)`
        : `doc link dangling: ${doc} → ${target} (대상 없음)`);
    }
  }
}

// 서비스 → 배선 간선. 배선 누락은 컴파일이 잡아주지 않고 런타임에 조용히 404/500 으로 나온다
// (📘msa-service-wiring, 실사고 36ac0234). 5곳 중 기계로 확정 가능한 3곳만 본다 —
// nginx 는 배포 형태가 여러 벌이라 단일 정본이 없고, JPA 스캔은 제한 스캔 서비스마다 정책이 달라
// 오탐이 난다. 나머지 2곳은 스킬 체크리스트가 담당한다.

// 루트 Dockerfile 은 의존 COPY(build.gradle.kts)와 소스 COPY 두 벌을 요구한다.
// 소스 COPY 가 빠지면 settings 평가는 통과하고 빌드가 뒤에서 깨진다 — 둘 다 있어야 배선이다.
export function parseDockerfileModules(dockerfile) {
  const text = String(dockerfile);
  const deps = new Set([...text.matchAll(/^COPY\s+([\w-]+)\/build\.gradle\.kts/gm)].map((m) => m[1]));
  const sources = new Set([...text.matchAll(/^COPY\s+([\w-]+)\s+\.\/[\w-]+/gm)].map((m) => m[1]));
  return [...deps].filter((module) => sources.has(module));
}

/**
 * ci.yml 이 서비스를 아는 방법은 둘 다 필요하다 — paths-filter 는 "무엇이 바뀌었나",
 * image 매핑은 "무엇을 빌드·푸시하나". 매핑에 없으면 테스트는 돌아도 이미지가 안 만들어진다.
 */
export function parseCiMatrixModules(yaml) {
  const text = String(yaml);
  const filters = [...text.matchAll(/^\s{8,}([a-z][\w-]*-service):\s*\[/gm)].map((m) => m[1]);
  const mapped = [...text.matchAll(/^\s*"([a-z][\w-]*-service)"\s*:\s*"/gm)].map((m) => m[1]);
  // 양쪽 모두에 있어야 배선된 것으로 본다(한쪽만 있으면 절반만 도는 상태다).
  return filters.filter((module) => mapped.includes(module));
}

/** compose 최상위 services 키만 본다(들여쓰기 2칸) — 중첩 블록의 키에 오탐하지 않게. */
export function parseComposeServices(yaml) {
  const text = String(yaml);
  const start = text.search(/^services:\s*$/m);
  if (start < 0) return [];
  const body = text.slice(start).split('\n').slice(1);
  const names = [];
  for (const line of body) {
    if (/^\S/.test(line)) break;            // 다음 최상위 키(volumes: 등)에서 멈춘다
    const match = line.match(/^ {2}([a-z][\w-]*):\s*$/);
    if (match) names.push(match[1]);
  }
  return names;
}

export function parseGatewayRouteIds(yaml) {
  return [...String(yaml).matchAll(/^\s*-\s*id:\s*(\S+)/gm)].map((m) => m[1]);
}

export function parseScanBasePackages(java) {
  const match = String(java).match(/scanBasePackages\s*=\s*(\{[^}]*\}|"[^"]+")/);
  return match ? [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]) : [];
}

const SPRING_STEREOTYPE = /@(RestController|Controller|Component|Service|Repository|Configuration|ControllerAdvice|ConfigurationProperties)\b/;
const DOMAIN_BASE = 'src/main/java/github/lms/lemuel/';

function validateServiceWiring(read, tracked, trackedSet, errors) {
  if (!trackedSet.has('settings.gradle.kts')) return;
  const modules = parseGradleModules(read('settings.gradle.kts'));

  if (trackedSet.has('Dockerfile')) {
    const copied = new Set(parseDockerfileModules(read('Dockerfile')));
    for (const module of modules) {
      if (!copied.has(module)) {
        errors.push(`service wiring: Dockerfile COPY 누락: ${module} (settings 평가가 실패해 전체 이미지 빌드가 깨진다)`);
      }
    }
  }

  // 배포 파이프라인 간선 — Dockerfile·gateway 가 맞아도 여기서 빠지면 이미지가 영원히 안 만들어지고
  // (CI 매트릭스) 로컬에서 뜨지도 않는다(compose). 둘 다 컴파일도 테스트도 잡아주지 않는다.
  const ciYml = '.github/workflows/ci.yml';
  if (trackedSet.has(ciYml)) {
    const wired = new Set(parseCiMatrixModules(read(ciYml)));
    for (const module of modules) {
      if (!wired.has(module)) {
        errors.push(`service wiring: CI 매트릭스 누락: ${module} (테스트는 전체 build 로 돌지만 이미지가 빌드·푸시되지 않는다)`);
      }
    }
  }

  const composeYml = 'docker-compose.yml';
  if (trackedSet.has(composeYml)) {
    const services = new Set(parseComposeServices(read(composeYml)));
    for (const module of modules) {
      if (!services.has(module)) {
        errors.push(`service wiring: docker-compose 누락: ${module} (로컬·통합 기동 대상이 아니라 배선 오류가 실행 전까지 안 드러난다)`);
      }
    }
  }

  const gatewayYml = 'gateway-service/src/main/resources/application.yml';
  if (trackedSet.has(gatewayYml)) {
    const ids = parseGatewayRouteIds(read(gatewayYml));
    for (const module of modules) {
      if (module === 'gateway-service') continue;
      // 라우트 id 는 모듈명 그대로이거나 접미가 붙는다(order-service-orders 처럼 경로군 분리).
      if (!ids.some((id) => id === module || id.startsWith(`${module}-`))) {
        errors.push(`service wiring: gateway 라우트 누락: ${module} (직접 포트는 되고 8080 경유만 404)`);
      }
    }
  }

  // 스프링 빈을 가진 도메인 패키지가 scanBasePackages 에 없으면 핸들러가 등록되지 않는다.
  // 스테레오타입이 없는 패키지(마커·DTO·순수 도메인)는 요구하지 않는다 — 요구하면 오탐만 는다.
  for (const module of modules) {
    const appPath = tracked.find((p) => p.startsWith(`${module}/src/main/java/`) && p.endsWith('Application.java'));
    if (!appPath) continue;
    const scanned = parseScanBasePackages(read(appPath));
    if (scanned.length === 0) continue; // 미선언 = 애플리케이션 패키지 이하 기본 스캔
    const base = `${module}/${DOMAIN_BASE}`;
    const packages = new Set();
    for (const path of tracked) {
      if (!path.startsWith(base) || !path.endsWith('.java')) continue;
      const rest = path.slice(base.length).split('/');
      if (rest.length > 1) packages.add(rest[0]);
    }
    for (const pkg of [...packages].sort()) {
      const full = `github.lms.lemuel.${pkg}`;
      if (scanned.some((s) => s === full || s === 'github.lms.lemuel' || full.startsWith(`${s}.`))) continue;
      const owned = tracked.filter((p) => p.startsWith(`${base + pkg}/`) && p.endsWith('.java'));
      if (owned.some((p) => SPRING_STEREOTYPE.test(read(p)))) {
        errors.push(`service wiring: scanBasePackages 누락: ${module} → ${full} (빈 미등록 — 핸들러 404 / 리포지토리 500)`);
      }
    }
  }
}

// 제출물 → 소유 서비스 간선. CLAUDE.md 배치 기준을 기계로 옮긴다.
// 제출물은 플러그인 매니페스트(.claude-plugin/.codex-plugin)로 식별한다 — 디렉토리 이름 규칙은
// 제출물마다 달라 신뢰할 수 없지만 매니페스트는 어느 플러그인에나 있다.
export function parseSubmissionRoots(tracked, modules) {
  const known = new Set(modules);
  const roots = new Map();
  for (const path of tracked) {
    if (!/\.(claude|codex)-plugin\/plugin\.json$/.test(path)) continue;
    const match = path.match(/^([\w-]+)\/src\/main\/resources\/([^/]+)\//);
    if (!match || !known.has(match[1])) {
      roots.set(path, null); // 소유 서비스 밖 — 위치 위반
      continue;
    }
    roots.set(`${match[1]}::${match[2]}`, { module: match[1], root: match[2] });
  }
  return [...roots].map(([key, value]) => value ?? { module: null, root: null, path: key });
}

function validateSubmissionPlacement(read, tracked, trackedSet, errors) {
  if (!trackedSet.has('settings.gradle.kts')) return;
  const modules = parseGradleModules(read('settings.gradle.kts'));
  for (const entry of parseSubmissionRoots(tracked, modules)) {
    if (entry.module === null) {
      errors.push(`submission placement: ${entry.path} (소유 서비스의 src/main/resources 밖 — 소유 서비스가 없는 제출물은 저장소에 두지 않는다)`);
      continue;
    }
    // jar 에 실리면 이미지가 부풀고 제출물이 배포물에 섞인다(company-service 실측 111M → 84K).
    const gradle = `${entry.module}/build.gradle.kts`;
    if (!trackedSet.has(gradle)) continue;
    if (!read(gradle).includes(`exclude("${entry.root}/**")`)) {
      errors.push(`submission jar leak: ${entry.module} → ${entry.root} (processResources exclude 누락 — 부트 jar 에 제출물이 실린다)`);
    }
  }
}

// ── 문서 사실 게이트 4종 ────────────────────────────────────────────────────────
// 로스터·라우팅은 위에서 잡지만, "신규 서비스가 붙은 뒤 상위 문서가 안 따라오는" 드리프트는
// 새는 축이 따로 있었다(2026-08-12 실측: card 구현 상태가 사실과 반대 · organization 소비처
// 미배선 주장 4곳 · 계약 토픽 12 vs 실측 36). 셋 다 코드/배선으로 기계 판정이 가능하다.
//
// 대상은 "현재 상태"를 기술하는 문서로 한정한다 — docs/adr·docs/superpowers·docs/seeds 는
// 시점 기록이라 지금 기준 부정확이 결함이 아니다(ADR 본문 보존 원칙). 오탐보다 범위 축소가 원칙:
// 세 규칙 모두 판정 근거가 같은 줄에 있을 때만 주장으로 인정한다.
const STATE_DOCS = [
  'CLAUDE.md', 'SPEC.md', 'README.md', 'HARNESS.md',
  'ARCHITECTURE.md', 'STRUCTURE.md', 'docs/DEVELOPMENT.md',
  'docs/PLAN.md', 'docs/PORTFOLIO.md', 'docs/DONE_CRITERIA.md',
];
const CONTRACT_EVENTS_DIR = 'shared-common/src/testFixtures/resources/contracts/events/';

// 1) 이벤트 계약 토픽 수 — "N토픽"/"N개 토픽" 주장 vs 실제 스키마 파일 수.
// 계약 코퍼스를 가리키는 줄만 본다(contracts/events·testFixtures·cross-service 언급).
// "6토픽 소비"(account GL) 같은 부분집합 주장은 이 키워드가 없어 대상 밖이다.
export function parseContractTopicClaims(markdown) {
  const claims = [];
  const lines = String(markdown).split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (!/contracts\/events|testFixtures|cross-service/.test(line)) continue;
    const match = line.match(/(\d+)\s*(?:개\s*)?토픽/);
    if (match) claims.push({ line: index + 1, claimed: Number(match[1]) });
  }
  return claims;
}

// 2) 구현 상태 — "REST/스케줄러/컨슈머 미구현·미배선" 주장 vs 해당 어댑터 디렉토리 실재.
// 어댑터가 실재하는데 미구현이라 적혀 있으면 실패. 같은 줄에 모듈과 종류가 함께 있을 때만 본다.
const ADAPTER_CLAIM_KINDS = [
  { keyword: 'REST', dir: 'adapter/in/web' },
  { keyword: '스케줄러', dir: 'adapter/in/schedule' },
  { keyword: '컨슈머', dir: 'adapter/in/kafka' },
];

export function parseUnimplementedClaims(markdown, modules) {
  const claims = [];
  const lines = String(markdown).split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (!/미구현|미배선/.test(line)) continue;
    for (const module of modules) {
      if (!line.includes(module)) continue;
      for (const kind of ADAPTER_CLAIM_KINDS) {
        if (line.includes(kind.keyword)) claims.push({ line: index + 1, module, ...kind });
      }
    }
  }
  return claims;
}

// 3) 소비처 배선 — "소비처 미배선" 주장 vs 다른 모듈 application.yml 의 토픽 참조.
// 줄에 토픽이 명시되면 그것을, 없으면 모듈명에서 도메인 접두(organization-service → lemuel.organization.*)를
// 끌어와 그 모듈이 발행하는 토픽으로 본다. 발행 모듈 자신의 yml 참조는 소비 근거가 아니다.
export function parseUnconsumedClaims(markdown, modules) {
  const claims = [];
  const lines = String(markdown).split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    // "소비처가 **아직** 미배선" 처럼 사이에 낀 짧은 부사까지 같은 주장으로 인정한다(2026-08-15 회귀).
    // 폭은 짧은 토큰 2개까지로 잠근다 — 더 넓히면 "소비처 목록은 … 라우터는 미배선" 같은 무관한 문장을 삼킨다.
    if (!/소비처\s*(?:가\s*)?(?:[^\s]{1,4}\s+){0,2}(?:미배선|없음)/.test(line)) continue;
    const topics = [...line.matchAll(/lemuel\.[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*/g)].map((m) => m[0]);
    const owners = modules.filter((module) => line.includes(module));
    if (topics.length > 0) claims.push({ line: index + 1, topics, owners });
    else if (owners.length > 0) claims.push({ line: index + 1, topics: [], owners });
  }
  return claims;
}

// 4) 서비스 수 — "N 마이크로서비스"/"N개 서비스" 주장 vs settings.gradle.kts 로스터(gateway 제외).
// 모듈 트리 대조(validateModuleRoster)는 트리 표기만 보므로 산문 주장이 새는 축이 따로 있었다
// (2026-08-15 실측: HARNESS.md 가 3곳에서 14 — 같은 문서 안의 "자바 16서비스" 와 자기모순).
// 전체 로스터 주장으로 인정하는 조건은 같은 줄의 앵커(API Gateway·gateway·DB-per-service) 하나뿐이다.
// 폴리글랏 합계 줄은 제외하고(코어 로스터가 아니다), 한 줄에 여러 수가 있으면 하나라도 실제와
// 맞으면 통과시킨다("Java 17종(16 서비스 + gateway) = 24 서비스" 같은 합산 표기 오탐 방지).
const SERVICE_ROSTER_ANCHOR = /API Gateway|gateway|게이트웨이|DB-per-service/i;

export function parseServiceCountClaims(markdown) {
  const claims = [];
  const lines = String(markdown).split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (!SERVICE_ROSTER_ANCHOR.test(line)) continue;
    if (/폴리글랏|polyglot/i.test(line)) continue;
    const counts = [...line.matchAll(/(\d+)\s*(?:개\s*)?(?:마이크로)?서비스/g)].map((m) => Number(m[1]));
    if (counts.length > 0) claims.push({ line: index + 1, counts });
  }
  return claims;
}

function validateDocFacts(read, tracked, trackedSet, errors) {
  const modules = trackedSet.has('settings.gradle.kts') ? parseGradleModules(read('settings.gradle.kts')) : [];
  const schemaCount = tracked.filter((p) => p.startsWith(CONTRACT_EVENTS_DIR) && p.endsWith('.schema.json')).length;
  const serviceCount = modules.filter((module) => module !== 'gateway-service').length;
  const ymlOf = (module) => {
    const path = `${module}/src/main/resources/application.yml`;
    return trackedSet.has(path) ? read(path) : '';
  };

  /**
   * YAML 주석을 걷어낸 본문 — 소비 배선 판정에 쓴다.
   *
   * 주석은 배선이 아니다. deposit 의 application.yml 은 `# ※ lemuel.card.authorized 는 아직
   * 구독하지 않는다` 라고 **구독하지 않는 이유**를 적어 두는데, 원문 substring 검색은 이를 소비
   * 근거로 읽어 SPEC 의 "소비처 미배선" 기술을 거짓으로 판정했다(2026-08-22). 설명이 자세할수록
   * 오탐이 늘어나는 검사였다 — 주석을 지우는 쪽이 정답이다.
   */
  const ymlBodyOf = (module) => stripYamlComments(ymlOf(module));

  // Boot 버전 정본은 build.gradle.kts — 문서가 같은 메이저의 다른 패치 버전을 말하면 드리프트다.
  // (Kotlin 폴리글랏의 Boot 3.x 표기는 메이저가 달라 대상 밖 — 오탐보다 범위 축소.)
  const gradleRoot = trackedSet.has('build.gradle.kts') ? read('build.gradle.kts') : '';
  const bootVersion = gradleRoot.match(/id\("org\.springframework\.boot"\)\s+version\s+"(\d+\.\d+\.\d+)"/)?.[1] ?? null;
  const bootMajor = bootVersion ? `${bootVersion.split('.')[0]}.` : null;

  for (const doc of STATE_DOCS) {
    if (!trackedSet.has(doc)) continue;
    const content = read(doc);

    if (bootVersion) {
      content.split('\n').forEach((lineText, idx) => {
        for (const m of lineText.matchAll(/Spring(?:%20| )Boot[^0-9\n]{0,4}(\d+\.\d+\.\d+)/gi)) {
          if (m[1].startsWith(bootMajor) && m[1] !== bootVersion) {
            errors.push(`doc facts: ${doc}:${idx + 1} Spring Boot 버전 드리프트: 문서=${m[1]} 실제=${bootVersion} (build.gradle.kts)`);
          }
        }
      });
    }

    if (serviceCount > 0) {
      for (const claim of parseServiceCountClaims(content)) {
        if (!claim.counts.includes(serviceCount)) {
          errors.push(`doc facts: ${doc}:${claim.line} 서비스 수 불일치: claimed=${claim.counts.join('/')} actual=${serviceCount} (settings.gradle.kts, gateway 제외)`);
        }
      }
    }

    if (schemaCount > 0) {
      for (const claim of parseContractTopicClaims(content)) {
        if (claim.claimed !== schemaCount) {
          errors.push(`doc facts: ${doc}:${claim.line} 이벤트 계약 토픽 수 불일치: claimed=${claim.claimed} actual=${schemaCount} (${CONTRACT_EVENTS_DIR}*.schema.json)`);
        }
      }
    }

    for (const claim of parseUnimplementedClaims(content, modules)) {
      const base = `${claim.module}/${DOMAIN_BASE}`;
      const exists = tracked.some((p) => p.startsWith(base) && p.includes(`/${claim.dir}/`) && p.endsWith('.java'));
      if (exists) {
        errors.push(`doc facts: ${doc}:${claim.line} 구현 상태 역전: ${claim.module} 의 ${claim.keyword}(${claim.dir})는 실재하는데 미구현/미배선으로 기술됨`);
      }
    }

    for (const claim of parseUnconsumedClaims(content, modules)) {
      let topics = claim.topics;
      if (topics.length === 0) {
        // 모듈만 지목된 주장 — 그 모듈 yml 이 선언한 자기 도메인 토픽을 발행분으로 본다.
        topics = claim.owners.flatMap((owner) => {
          const prefix = `lemuel.${owner.replace(/-service$/, '').replace(/-/g, '')}.`;
          return [...ymlBodyOf(owner).matchAll(/lemuel\.[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*/g)]
            .map((m) => m[0])
            .filter((topic) => topic.startsWith(prefix));
        });
      }
      for (const topic of [...new Set(topics)]) {
        // 발행 모듈의 자기 yml 참조는 소비 근거가 아니다. 발행자는 줄이 지목한 모듈 + 토픽
        // 도메인에서 도출한다(lemuel.card.* → card-service) — 표 행처럼 모듈명이 축약된 경우 대비.
        const domain = topic.split('.')[1];
        const producers = new Set(claim.owners);
        for (const module of modules) {
          if (module.replace(/-service$/, '').replace(/-/g, '') === domain) producers.add(module);
        }
        const consumer = modules.find((module) => !producers.has(module) && ymlBodyOf(module).includes(topic));
        if (consumer) {
          errors.push(`doc facts: ${doc}:${claim.line} 소비처 배선 있음: ${topic} 를 ${consumer} 가 참조하는데 "소비처 미배선"으로 기술됨`);
        }
      }
    }
  }
}

function validateStatus(status, tracked, errors) {
  const checks = [
    ['application.yml', tracked.filter((p) => /\/src\/main\/resources\/application\.yml$/.test(p)).length, [/application\.yml[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i, /application\.yml[^\n]*?→\s*(\d[\d,]*)/i]],
    ['migration', tracked.filter((p) => /\/src\/main\/resources\/db\/migration\/.*\.sql$/.test(p)).length, [/(?:Flyway|migration|마이그레이션)[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i]],
    ['ADR', tracked.filter((p) => /^docs\/adr\/\d.*\.md$/.test(p)).length, [/ADR[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i]],
    ['test classes', tracked.filter((p) => /\/src\/test\/.*(?:Test|Tests|IT)\.java$/.test(p)).length, [/(?:test classes|테스트 클래스)[^\n]*?\*\*(\d[\d,]*)[^\d\n*]*\*\*/i]],
  ];
  for (const [label, actual, patterns] of checks) {
    const claimed = claimNumber(status, patterns);
    if (claimed === null) errors.push(`STATUS ${label} claim missing (actual=${actual})`);
    else if (claimed !== actual) errors.push(`STATUS ${label} mismatch: claimed=${claimed} actual=${actual}`);
  }
}

export function collectAudit(repoRoot, manifest) {
  validateManifest(manifest);
  const root = resolve(repoRoot);
  const tracked = trackedFiles(root);
  const trackedSet = new Set(tracked);
  const errors = [];
  const read = (path) => readFileSync(resolve(root, ...path.split('/')), 'utf8');

  for (const path of manifest.requiredTrackedFiles) {
    if (!existsSync(resolve(root, ...path.split('/')))) errors.push(`${path}: required file missing`);
    else if (!trackedSet.has(path)) errors.push(`${path}: required file not tracked`);
  }

  const referenceSources = tracked.filter((p) => /^\.claude\/commands\/.*\.md$/.test(p));
  if (trackedSet.has('.claude/settings.json')) referenceSources.push('.claude/settings.json');
  for (const source of referenceSources) {
    const refs = new Set([...read(source).matchAll(/scripts\/harness\/[\w./-]+\.(?:mjs|sh)/g)].map((m) => m[0]));
    for (const ref of refs) if (!trackedSet.has(ref)) errors.push(`broken reference in ${source}: ${ref} is not tracked`);
  }

  if (trackedSet.has('STATUS.md')) validateStatus(read('STATUS.md'), tracked, errors);
  validateModuleRoster(read, trackedSet, errors);
  validateSettlementDomainRoster(read, tracked, trackedSet, errors);
  validateRoutingMap(read, trackedSet, errors);
  validatePluginGuardPaths(read, tracked, trackedSet, errors);
  validateDocLinks(root, read, tracked, trackedSet, manifest, errors);
  validateServiceWiring(read, tracked, trackedSet, errors);
  validateSubmissionPlacement(read, tracked, trackedSet, errors);
  validateDocFacts(read, tracked, trackedSet, errors);

  for (const pair of manifest.criticalContractPairs) {
    if (!trackedSet.has(pair.claude) || !trackedSet.has(pair.codex)) continue;
    try {
      if (Object.hasOwn(pair, 'facts')) {
        const claude = extractHarnessContract(read(pair.claude));
        const codex = extractHarnessContract(read(pair.codex));
        if (!deepEqual(claude, pair.facts) || !deepEqual(codex, pair.facts)) errors.push(`${pair.contract} facts mismatch`);
      } else {
        const claude = readContractCases(read(pair.claude));
        const codex = readContractCases(read(pair.codex));
        if (!deepEqual(claude, pair.contractCases)) errors.push(`${pair.contract} claude contract cases mismatch`);
        if (!deepEqual(codex, pair.contractCases)) errors.push(`${pair.contract} codex contract cases mismatch`);
      }
    } catch (error) {
      errors.push(`${pair.contract}: ${error.message}`);
    }
  }

  // 컨텍스트 예산(KPI-5): 세션마다 강제 로드되는 상주 문서 vs 온디맨드 스킬의 바이트.
  // 상주 비중이 늘면 온디맨드 설계(스킬 분리)가 무너지고 있다는 신호 — 정보 지표, 게이트 아님.
  const bytesOf = (path) => {
    try { return trackedSet.has(path) ? Buffer.byteLength(read(path), 'utf8') : 0; } catch { return 0; }
  };
  const skillFiles = tracked.filter((p) => /^\.claude\/skills\/.*\/SKILL\.md$/.test(p));
  const contextBudget = {
    residentBytes: bytesOf('CLAUDE.md'),
    onDemandSkillCount: skillFiles.length,
    onDemandSkillBytes: skillFiles.reduce((sum, path) => sum + bytesOf(path), 0),
  };

  return {
    checks: [],
    failures: errors,
    errors,
    contextBudget,
    inventory: {
      trackedFiles: tracked,
      agents: tracked.filter((p) => /^\.claude\/agents\/.*\.md$/.test(p)).length,
      skills: skillFiles.length,
      commands: tracked.filter((p) => /^\.claude\/commands\/.*\.md$/.test(p)).length,
    },
  };
}

export async function runAuditCli(args, io = {}) {
  const stdout = io.stdout ?? ((text) => process.stdout.write(text));
  const stderr = io.stderr ?? ((text) => process.stderr.write(text));
  const value = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index === -1 ? fallback : args[index + 1];
  };
  try {
    for (let index = 0; index < args.length; index += 2) {
      if (!['--root', '--manifest'].includes(args[index])) throw new Error(`unsupported argument: ${args[index]}`);
      if (!args[index + 1] || args[index + 1].startsWith('--')) throw new Error(`missing value for argument: ${args[index]}`);
    }
    const root = resolve(value('--root', process.cwd()));
    const manifestPath = value('--manifest', 'scripts/harness/manifest.json');
    assertPath(manifestPath, '--manifest');
    const manifest = validateManifest(JSON.parse(readFileSync(resolve(root, ...manifestPath.split('/')), 'utf8')));
    const result = collectAudit(root, manifest);
    for (const error of result.errors) stdout(`FAIL ${error}\n`);
    const { residentBytes, onDemandSkillCount, onDemandSkillBytes } = result.contextBudget;
    const totalBytes = residentBytes + onDemandSkillBytes;
    const kb = (bytes) => `${(bytes / 1024).toFixed(1)}KB`;
    stdout(`info resident-context: 상주 CLAUDE.md ${kb(residentBytes)} · 온디맨드 스킬 ${onDemandSkillCount}개 ${kb(onDemandSkillBytes)} (상주 비중 ${totalBytes ? Math.round((residentBytes / totalBytes) * 100) : 0}%)\n`);
    stdout(result.errors.length ? `harness-audit: ${result.errors.length} failure(s)\n` : 'harness-audit: healthy\n');
    return result.errors.length ? 1 : 0;
  } catch (error) {
    stderr(`harness-audit: ${error.message}\n`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runAuditCli(process.argv.slice(2));
}
