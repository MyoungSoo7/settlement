# 개발 참조 — Lemuel

> CLAUDE.md 에서 분리한 **참조성 정보**(기술 스택·빌드 커맨드·인프라·작업 이력).
> 에이전트가 매 대화에 상주시킬 필요는 없고 필요할 때 조회한다. 강제 규칙·가드레일·DoD 는 [`../CLAUDE.md`](../CLAUDE.md) 참조.

## 기술 스택

| 구분 | 기술 | 구분 | 기술 |
|------|------|------|------|
| 언어 | Java 25 | 메시지 | Kafka (Redpanda 호환) |
| 프레임워크 | Spring Boot 4.0.7 | PG | Toss Payments |
| 빌드 | Gradle 멀티모듈 (Kotlin DSL) | 배치 | Spring Batch |
| Gateway | Spring Cloud Gateway 2025 | 캐시 | Caffeine(L1) + 선택 Redis(L2) |
| DB | PostgreSQL 17 | PDF | iText 8 |
| 검색 | Elasticsearch 8.17 | 마이그레이션 | Flyway (V1~V50 + `V{timestamp}__` 혼재) |
| 관측 | Micrometer + Prometheus | 회복탄력성/RateLimit | Resilience4j / Bucket4j |

> Boot 4 / Java 25 조합의 알려진 함정(레거시 ObjectMapper 빈 부재, RestClient.Builder 자체 빈 필요,
> 네이티브 @Query 구조적 SpEL 미평가, ArchUnit 1.4.x+ 필요 등)은 각 서비스 코드·ADR 참조.

## 빌드 및 실행 커맨드

```bash
./gradlew build                                     # 전체 빌드
./gradlew :<module>:compileJava                     # 모듈별 컴파일 (예: :order-service:compileJava)
./gradlew :<module>:test                            # 모듈별 테스트
./gradlew :<module>:jacocoTestCoverageVerification  # 커버리지 게이트(측정 정답, LINE 90%)
./gradlew :<module>:bootRun                         # 모듈별 부트런
./gradlew :<module>:bootJar                         # 모듈별 jar

# 모듈(19 = 18 서비스 + gateway, 정본 settings.gradle.kts): shared-common, order-service, settlement-service,
#       loan-service, financial-statements-service, economics-service, company-service, operation-service,
#       market-service, ai-service, common-data-service, investment-service, account-service,
#       organization-service, card-service, insurance-service, deposit-service, board-service,
#       education-service, gateway-service

# Docker
docker compose up -d                                # DB-per-service PG 18종 · ES · Redpanda · 앱 컨테이너 21개(JVM 19 = 18 서비스+gateway, + market-stream + notification) · frontend
docker build --build-arg MODULE=<service> -t lemuel-<name> .   # 컨테이너 이미지 (MODULE 로 서비스 지정)
```

> `bootRun` 은 `../.env` 를 자동으로 읽지 못한다 — 필요한 env 는 `--args` 또는 System property 로 주입.

## 인프라

- 컨테이너: Docker Compose(로컬), Kubernetes(운영). 리버스 프록시: gateway-service.
- 모니터링: Prometheus + Micrometer + Grafana + OTLP. 메시지: Redpanda(Kafka 호환).

## 작업 이력 / 브랜치 정보

- **메인 라인**: `develop` → `main`. main 은 보호 브랜치(PR 필수, squash 만, **필수 CI 6종** — 목록은 [`CLAUDE.md`](../CLAUDE.md) 작업 프로토콜 절). 분리 전 백업 `backup/pre-msa-split`.
- **MSA 분리 완료**: 18 서비스 + gateway, 전 서비스 DB-per-service, settlement↔order 이벤트 프로젝션(ADR 0020).
- **제거된 도메인**: `reservation`(시공 예약) — 모듈·DB·라우팅·프론트·k8s 정리 완료.
- **TPS 개선**: PgBouncer→Redpanda, settlement 배치/컨슈머 스레드·프로젝션 쿼리·캐시·PDF 비동기화 등.
- **CI 백엔드 병렬화 (2026-08-14)**: 백엔드 게이트가 단일 잡으로 18모듈을 순차 실행해 14~43분이
  걸렸고, 그 사이 다음 push 가 오면 concurrency 대기 슬롯(그룹당 1개)에서 밀려 취소됐다 —
  **develop `ci` 20건 중 완주 2건**(실측). 즉 push 가 잦으면 게이트가 판정을 내지 못했다.
  ① 모듈 매트릭스로 분할(`backend-test`) + 집계 잡(`backend-ci`)이 SBOM·Trivy·Sonar·커버리지
  코멘트를 한 번만 수행, ② 최대 병목 `settlement-service` 는 포크 병렬화(`maxParallelForks`).

  | 구간                        | 이전  | 이후                    |
  | --------------------------- | ----- | ----------------------- |
  | 백엔드 게이트(벽시계)       | 29분  | 11분 = 가장 느린 모듈   |
  | 전체 `ci` run               | —     | 15분                    |
  | `settlement-service` 테스트 | 9분36초(forks=1) | 6분2초(forks=2, **-37%**) |

  모듈별(2026-08-14 run 31762060415): settlement 11분 · order 7분 · card 5분 · 나머지 2~4분 ·
  집계 잡 3분. 포크 벤치마크는 격리 worktree + Docker 기동으로 측정했고 테스트 1,409개 ·
  skip 1 · 실패 0 · JaCoCo 게이트 통과(포크가 exec 데이터를 손상시키지 않음)를 함께 확인했다.

  **잡을 더 쪼개지 않은 이유**: `jacocoTestCoverageVerification`(LINE 90%)이 모듈 단위라, 한 모듈의
  테스트를 CI 잡 여러 개로 나누면 각 샤드가 부분 커버리지만 갖게 되어 전부 게이트에 걸린다.
  우회하려면 `.exec` 를 아티팩트로 모아 병합 후 검증하는 배선이 필요한데, 로컬 검증이 불가능해
  CI 에서만 확인 가능해진다. 포크 병렬화는 커버리지 산정 단위를 건드리지 않는다.

  **집계 잡 이름(`Backend - Build/Test/JaCoCo/SonarCloud`)은 바꾸지 말 것** — ruleset 의 필수 상태
  체크로 등록된 문자열이고, 매트릭스 잡 이름은 가변이라 필수로 걸 수 없다. 또한 집계 잡에는
  `always()` + needs 결과 명시 검사가 걸려 있다: 기본 동작인 skip 은 필수 체크에서 통과로
  취급되어, 모듈 하나가 깨져도 게이트가 조용히 사라지기 때문이다.
