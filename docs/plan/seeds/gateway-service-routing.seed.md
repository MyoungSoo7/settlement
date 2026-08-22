# Seed — gateway-service 라우팅 표면 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`gateway-service-routing.seed.yaml`](gateway-service-routing.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**gateway-service(Spring Cloud Gateway WebFlux 8080 — 라우팅 20건·프리픽스 무변경 전달·위성 admin 미노출·
폴리글랏 2종 RewritePath)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                          | 제외                                    |
| --------------------------------------------- | --------------------------------------- |
| 라우트 20건 (predicate·URI 환경변수·필터)     | 각 백엔드의 인증/인가·도메인 규칙       |
| 노출면 정책 (위성 admin 미등록·등급 분리)     | k8s Ingress·helm 차트                   |
| 런타임 형태 (reactive·무상태·actuator)        |                                         |
| 배포 배선 (compose 환경변수·nginx 프록시 경계) |                                         |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **라우트 20건** — 18 Java 서비스 **전부** + 폴리글랏 2종. 모든 uri 가 `${<SVC>_SERVICE_URI:localhost:포트}` (`application.yml:14-130`).
2. **프리픽스 무변경** — order 의 `/api` 유무 혼재를 통일하지 않고 양쪽 열거. 필터는 `RewritePath` 2건뿐 (`:16-19,143,158`).
3. **노출면 = 라우트 목록** — 위성 5종은 공개 조회만, 수집 트리거 `/admin/**` 미등록으로 차단 (`:31-64` 주석 명문).
4. **등급이 다르면 안 합친다** — deposit `/api`(읽기)·`/admin`(잔고 이동)을 와일드카드로 묶지 않음 (`:129-133`).
5. **notification 은 정확일치 1건** — `/notifications/send`·`/demo` 는 무인증 발송 경로라 와일드카드 금지 (`:154-157`).
6. **완전 무상태** — 앱 클래스 11줄 + YAML 이 전부. 컨트롤러·필터·설정 클래스 0, DB·세션 0.
7. **인증하지 않는다** — 게이트웨이 통과 ≠ 인증 통과. JWT 는 각 서비스 보안 체인이 검증.
8. **공급망 핀** — `bcprov-jdk18on` 1.81(CVE-2025-14813 CRITICAL)을 constraint 로 1.84 상향 (`build.gradle.kts:8-19`).

## 이벤트 계약

**없음 — 순수 HTTP 라우터.** Kafka 의존이 빌드에 아예 없다.

## 수용 기준 (게이트 매핑)

| AC   | 기준                                   | 게이트                                                    |
| ---- | -------------------------------------- | --------------------------------------------------------- |
| AC-1 | reactive 스택 컨텍스트 부팅            | `GatewayServiceApplicationTest.contextLoads` (RANDOM_PORT) |
| AC-2 | 라우트가 `RouteLocator` 에 로드        | `routesAreConfigured` (단 5/20 만 어서트 — KI-3)          |
| AC-3 | 이미지 CRITICAL CVE 0                  | Trivy image scan (CRITICAL gate)                          |
| AC-4 | 라우트 대상 로스터가 gradle 과 일치    | `node scripts/harness/harness-audit.mjs`                  |

## Known Issues (발견만 기록)

- **KI-1 ★high**: notification 라우트가 compose 에서 **도달 불가** — 컨테이너 정의도 `NOTIFICATION_SERVICE_URI` 도 없어 기본값 `localhost:8130` 이 게이트웨이 자신을 가리킨다. CLAUDE.md 의 "폴리글랏 2종 compose 배선" 서술은 market-stream 한쪽만 사실.
- **KI-2 ★high**: `/api/notifications/stream` 에 nginx 무버퍼 location 부재 — 범용 location 의 `proxy_buffering on` + `read_timeout 60s` 에 걸린다.
- **KI-3**: 라우트 테스트가 20건 중 5건만 어서트 — 나머지 15건은 회귀 보호 밖.
- **KI-4**: 경로 화이트리스트 수기 유지(order 32·settlement 22) — 누락 시 런타임 404. nginx 는 이미 allowlist 를 버렸으나 게이트웨이만 유지(트레이드오프 명문).
- **KI-5**: `SPRING_PROFILES_ACTIVE=prod` 주입되나 `application-prod.yml` 부재.
- **KI-6**: `depends_on` 이 order-service 하나뿐 — 백엔드 도달성이 헬스에 미반영(by-design).
- **KI-7**: CORS·RateLimit 미배치 — RateLimit 은 각 서비스 Bucket4j 담당(의도).
